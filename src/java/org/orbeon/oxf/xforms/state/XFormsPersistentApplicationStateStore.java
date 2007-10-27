/**
 *  Copyright (C) 2007 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.state;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.DocumentResult;
import org.orbeon.oxf.cache.CacheLinkedList;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.pipeline.StaticExternalContext;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.Datasource;
import org.orbeon.oxf.processor.xmldb.XMLDBProcessor;
import org.orbeon.oxf.xforms.XFormsProperties;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.processor.XFormsServer;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.dom4j.LocationDocumentResult;
import org.orbeon.saxon.om.FastStringBuffer;
import org.xml.sax.ContentHandler;
import org.xmldb.api.base.Collection;
import org.xmldb.api.base.Resource;
import org.xmldb.api.base.XMLDBException;
import org.xmldb.api.modules.XMLResource;

import javax.xml.transform.sax.TransformerHandler;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This store:
 *
 * o keeps XFormsState instances into an application store and persists data going over a given size.
 * o leverages the underlying memory store
 * o is shared among all users and sessions
 *
 * Here is how things work:
 *
 * o When an entry from the memory store is expiring, it is migrated to the persistent store.
 * o When an entry is not found in the memory store, it is searched for in the persistent store.
 * o A session id is added when available.
 * o When a session expires, both memory and persistent entries are expired.
 * o Upon first use, all persistent entries with session information are expired.
 *
 * NOTE about session ids: a single entry can have multiple session ids in the case of static states. This means that
 * we must be careful:
 *
 * o Merge session information upon persisting an entry
 * o Remove entry only once last session id is gone in memory
 * o Remove entry only once last session id is gone in the persistent store
 */
public class XFormsPersistentApplicationStateStore extends XFormsStateStore {

    private static final boolean TEMP_PERF_TEST = false;
    private static final int TEMP_PERF_ITERATIONS = 100;

    private static final String PERSISTENT_STATE_STORE_APPLICATION_KEY = "oxf.xforms.state.store.persistent-application-key";
    private static final String XFORMS_STATE_STORE_LISTENER_STATE_KEY = "oxf.xforms.state.store.has-session-listeners-key";

    // For now the driver is not configurable, but everything else (URI, username, password, collection) is configurable in properties
    private static final String EXIST_XMLDB_DRIVER = "org.exist.xmldb.DatabaseImpl";

    // Access to the XML:DB API
    private static final XMLDBAccessor XMLDB_ACCESSOR = new XMLDBAccessor();

    // Map session ids -> Map of keys
    private final Map sessionToKeysMap = new HashMap();

    /**
     * Create an instance of this state store.
     *
     * @param externalContext   external context
     * @return                  state store
     */
    public synchronized static XFormsStateStore instance(ExternalContext externalContext) {
        // Try to find existing store
        {
            final XFormsStateStore existingStateStore
                    = (XFormsStateStore) externalContext.getAttributesMap().get(PERSISTENT_STATE_STORE_APPLICATION_KEY);

            if (existingStateStore != null)
                return existingStateStore;
        }
        // Create new store
        {
            final XFormsPersistentApplicationStateStore newStateStore = new XFormsPersistentApplicationStateStore();

            // Expire remaining persistent entries with session information
            newStateStore.expireAllPersistentWithSession();
//            newStateStore.expireAllPersistent();

            // Keep new store in application scope
            externalContext.getAttributesMap().put(PERSISTENT_STATE_STORE_APPLICATION_KEY, newStateStore);
            return newStateStore;
        }
    }

    protected int getMaxSize() {
        return XFormsProperties.getApplicationStateStoreSize();
    }

    protected String getStoreDebugName() {
        return "global application";
    }

    public synchronized void add(String pageGenerationId, String oldRequestId, String requestId, XFormsState xformsState, final String sessionId) {

        // Do the operation
        super.add(pageGenerationId, oldRequestId, requestId, xformsState, sessionId);

        // Add session listener if needed
        if (sessionId != null) {
            final ExternalContext.Session session = getExternalContext().getSession(XFormsStateManager.FORCE_SESSION_CREATION);
            if (session != null) {

                // Just a consistency check
                if (session.getId() != sessionId)
                    throw new OXFException("Inconsistent session ids when persiting XForms state store entry.");

                // We want to register only one expiration listener per session
                final Map sessionAttributes = session.getAttributesMap(ExternalContext.Session.APPLICATION_SCOPE);
                if (sessionAttributes.get(XFORMS_STATE_STORE_LISTENER_STATE_KEY) == null) {
                    session.addListener(new ExternalContext.Session.SessionListener() {
                        public void sessionDestroyed() {
                            // Expire both memory and persistent entries
                            expireMemoryBySession(sessionId);
                            expirePersistentBySession(sessionId);
                        }
                    });
                    sessionAttributes.put(XFORMS_STATE_STORE_LISTENER_STATE_KEY, "");
                }
            }
        }
    }

    // NOTE: The super() method doesn't do anything
    protected void persistEntry(StoreEntry storeEntry) {

        if (XFormsServer.logger.isDebugEnabled()) {
            debug("persisting entry for key: " + storeEntry.key + " (" + (storeEntry.value.length() * 2) + " bytes).");
        }

        final PipelineContext pipelineContext = getPipelineContext();

        if (TEMP_PERF_TEST) {

            // Do the operation TEMP_PERF_ITERATIONS times to test performance
            final long startTime = System.currentTimeMillis();
            for (int i = 0; i < TEMP_PERF_ITERATIONS; i ++) {
                persistEntryExistXMLDB(pipelineContext, storeEntry);
            }
            debug("average write persistence time: " + ((System.currentTimeMillis() - startTime) / TEMP_PERF_ITERATIONS) + " ms." );

        } else {
            persistEntryExistXMLDB(pipelineContext, storeEntry);
        }
    }

    // NOTE: This calls super() and handles session information
    protected void addOrReplaceOne(String key, String value, boolean isInitialEntry, String currentSessionId) {

        // Actually add
        super.addOrReplaceOne(key, value, isInitialEntry, currentSessionId);

        // Remember that this key is associated with a session
        if (currentSessionId != null) {
            Map sessionMap = (Map) sessionToKeysMap.get(currentSessionId);
            if (sessionMap == null) {
                sessionMap = new HashMap();
                sessionToKeysMap.put(currentSessionId, sessionMap);
            }
            sessionMap.put(key, "");
        }
    }

    // This calls super() and handles session information
    protected void removeStoreEntry(CacheLinkedList.ListEntry existingListEntry) {

        // Actually remove
        super.removeStoreEntry(existingListEntry);

        // Remove the session id -> key mappings related to this entry
        final StoreEntry existingStoreEntry = (StoreEntry) existingListEntry.element;
        if (existingStoreEntry.sessionIds.size() > 0) {
            for (Iterator i = existingStoreEntry.sessionIds.keySet().iterator(); i.hasNext();) {
                final String currentSessionId = (String) i.next();

                final Map sessionMap = (Map) sessionToKeysMap.get(currentSessionId);
                if (sessionMap != null) {
                    sessionMap.remove(existingStoreEntry.key);
                }
            }
        }
    }

    private void persistEntryExistXMLDB(PipelineContext pipelineContext, StoreEntry storeEntry) {
        final String messageBody = encodeMessageBody(pipelineContext, storeEntry);
        try {
            final StoreEntry existingStoreEntry = findPersistedEntryExistXMLDB(storeEntry.key);
            if (existingStoreEntry != null) {
                // Merge existing session ids
                final int currentSessionIdCount = storeEntry.sessionIds.size();
                storeEntry.sessionIds.putAll(existingStoreEntry.sessionIds);
                debug("merged session ids for key: " + storeEntry.key + " (" + (storeEntry.sessionIds.size() - currentSessionIdCount) + " ids).");
            }

            XMLDB_ACCESSOR.storeResource(pipelineContext, new Datasource(EXIST_XMLDB_DRIVER,
                    XFormsProperties.getStoreURI(), XFormsProperties.getStoreUsername(), XFormsProperties.getStorePassword()), XFormsProperties.getStoreCollection(),
                    true, storeEntry.key, messageBody);
        } catch (Exception e) {
            throw new OXFException("Unable to store entry in persistent state store for key: " + storeEntry.key, e);
        }
    }

    /**
     * Remove all memory entries which have the given session id.
     *
     * @param sessionId     Servlet session id
     */
    private void expireMemoryBySession(String sessionId) {

        final Map sessionMap = (Map) sessionToKeysMap.get(sessionId);
        if (sessionMap != null) {
            final int storeSizeBeforeExpire = getCurrentStoreSize();
            int expiredCount = 0;
            if (sessionMap.size() > 0) {
                for (Iterator i = sessionMap.keySet().iterator(); i.hasNext();) {
                    final String currentKey = (String) i.next();
                    final CacheLinkedList.ListEntry currentListEntry = findEntry(currentKey);
                    final StoreEntry currentStoreEntry = (StoreEntry) currentListEntry.element;

                    // Remove session id from list of session ids
                    currentStoreEntry.sessionIds.remove(sessionId);

                    // Remove entry once there is no more associated session
                    if (currentStoreEntry.sessionIds.size() == 0) {
                        super.removeStoreEntry(currentListEntry);
                        expiredCount++;
                    }
                }
            }
            sessionToKeysMap.remove(sessionId);

            if (expiredCount > 0 && XFormsServer.logger.isDebugEnabled())
                debug("expired " + expiredCount + " entries for session " + sessionId + " (" + (storeSizeBeforeExpire - getCurrentStoreSize()) + " bytes).");
        }
    }

    /**
     * Remove all persisted entries which have the given session id.
     *
     * @param sessionId     Servlet session id
     */
    private void expirePersistentBySession(String sessionId) {

        // 1. Remove documents having only one session-id element left equal to this session id
        // 2. Remove all session-id elements equal to this session id
        final String query = "xquery version \"1.0\";" +
            "                 declare namespace xmldb=\"http://exist-db.org/xquery/xmldb\";" +
            "                 declare namespace util=\"http://exist-db.org/xquery/util\";" +
            "                 <result>" +
            "                   {" +
            "                     (count(for $entry in /entry[session-id = '" + sessionId + "' and count(session-id) = 1]" +
            "                           return (xmldb:remove(util:collection-name($entry), util:document-name($entry)), ''))," +
            "                     for $session-id in /entry/session-id[. = '" + sessionId + "'] return update delete $session-id)" +
            "                   }" +
            "                 </result>";

        final Document result = executeQuery(query);
        final int count = Integer.parseInt(result.getDocument().getRootElement().getStringValue());
        debug("expired " + count + " persistent entries for session (" + sessionId + ").");
    }

    private void expireAllPersistentWithSession() {

        final String query = "xquery version \"1.0\";" +
            "                 declare namespace xmldb=\"http://exist-db.org/xquery/xmldb\";" +
            "                 declare namespace util=\"http://exist-db.org/xquery/util\";" +
            "                 <result>" +
            "                   {" +
            "                     count(for $entry in /entry[session-id]" +
            "                           return (xmldb:remove(util:collection-name($entry), util:document-name($entry)), ''))" +
            "                   }" +
            "                 </result>";

        final Document result = executeQuery(query);
        final int count = Integer.parseInt(result.getRootElement().getStringValue());
        debug("expired " + count + " persistent entries with session information.");
    }

    public void expireAllPersistent() {

        final String query = "xquery version \"1.0\";" +
            "                 declare namespace xmldb=\"http://exist-db.org/xquery/xmldb\";" +
            "                 declare namespace util=\"http://exist-db.org/xquery/util\";" +
            "                 <result>" +
            "                   {" +
            "                     count(for $entry in /entry" +
            "                           return (xmldb:remove(util:collection-name($entry), util:document-name($entry)), ''))" +
            "                   }" +
            "                 </result>";

        final Document result = executeQuery(query);
        final int count = Integer.parseInt(result.getRootElement().getStringValue());
        debug("expired " + count + " persistent entries.");
    }

    private Document executeQuery(String query) {

        final DocumentResult result = new DocumentResult();
        final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
        identity.setResult(result);

        XMLDB_ACCESSOR.query(getPipelineContext(), new Datasource(EXIST_XMLDB_DRIVER,
                XFormsProperties.getStoreURI(), XFormsProperties.getStoreUsername(), XFormsProperties.getStorePassword()), XFormsProperties.getStoreCollection(),
                true, null, query, null, identity);

        return result.getDocument();
    }

    private PipelineContext getPipelineContext() {
        // NOTE: We may not have a StaticContext when we are called from a session listener, but that should be ok
        // (PipelineContext is used further down the line to ensure that the db drive is registered, but it should
        // be.)
        final StaticExternalContext.StaticContext staticContext = StaticExternalContext.getStaticContext();
        return (staticContext != null) ? staticContext.getPipelineContext() : null;
    }

    private ExternalContext getExternalContext() {
            final StaticExternalContext.StaticContext staticContext = StaticExternalContext.getStaticContext();
            return (staticContext != null) ? staticContext.getExternalContext() : null;
    }

    private String encodeMessageBody(PipelineContext pipelineContext, StoreEntry storeEntry) {

        final FastStringBuffer sb = new FastStringBuffer("<entry><key>");
        sb.append(storeEntry.key);
        sb.append("</key><value>");

        // Store the value and make sure it is encrypted as it will be externalized
        final String encryptedValue;
        if (storeEntry.value.startsWith("X3") || storeEntry.value.startsWith("X4")) {
            // Data is currently not encrypted, so encrypt it
            final byte[] decodedValue = XFormsUtils.decodeBytes(pipelineContext, storeEntry.value, XFormsProperties.getXFormsPassword());
            encryptedValue = XFormsUtils.encodeBytes(pipelineContext, decodedValue, XFormsProperties.getXFormsPassword());
        } else {
            // Data is already encrypted
            encryptedValue = storeEntry.value;
        }

        sb.append(encryptedValue);
        sb.append("</value>");

        // Store the session ids if any
        final Map sessionIds = storeEntry.sessionIds;
        if (sessionIds != null && sessionIds.size() > 0) {
            for (Iterator i = sessionIds.keySet().iterator(); i.hasNext();) {
                final String currentSessionId = (String) i.next();
                sb.append("<session-id>");
                sb.append(currentSessionId);
                sb.append("</session-id>");
            }
        }

        // Store the initial entry flag
        sb.append("<is-initial-entry>");
        sb.append(Boolean.toString(storeEntry.isInitialEntry));
        sb.append("</is-initial-entry></entry>");

        return sb.toString();
    }

    // NOTE: The super() method doesn't do anything
    protected String findPersistedEntry(String key) {

        if (XFormsServer.logger.isDebugEnabled()) {
            debug("finding persisting entry for key: " + key + ".");
        }

        // Call persistent store
        final StoreEntry persistedStoreEntry;
        if (TEMP_PERF_TEST) {

            // Do the operation TEMP_PERF_ITERATIONS times to test performance
            StoreEntry tempResult = null;
            final long startTime = System.currentTimeMillis();
            for (int i = 0; i < TEMP_PERF_ITERATIONS; i ++) {
                tempResult = findPersistedEntryExistXMLDB(key);
                if (tempResult == null)
                    break;
            }
            if (tempResult != null)
                debug("average read persistence time: " + ((System.currentTimeMillis() - startTime) / TEMP_PERF_ITERATIONS) + " ms." );

            persistedStoreEntry = tempResult;

        } else {
            persistedStoreEntry = findPersistedEntryExistXMLDB(key);
        }

        // Handle result
        if (persistedStoreEntry != null) {
            // Add the key to the list in memory
            addOne(persistedStoreEntry.key, persistedStoreEntry.value, persistedStoreEntry.isInitialEntry, persistedStoreEntry.sessionIds);
            debug("migrated persisted entry for key: " + key);
            return persistedStoreEntry.value;
        } else {
            // Not found
            debug("did not find entry in persistent store for key: " + key);
            return null;
        }
    }

    private StoreEntry findPersistedEntryExistXMLDB(String key) {

        final PipelineContext pipelineContext = getPipelineContext();

        final Document document;
        try {
            document = XMLDB_ACCESSOR.getResource(pipelineContext, new Datasource(EXIST_XMLDB_DRIVER,
                    XFormsProperties.getStoreURI(), XFormsProperties.getStoreUsername(), XFormsProperties.getStorePassword()),
                    XFormsProperties.getStoreCollection(), true, key);
        } catch (Exception e) {
            throw new OXFException("Unable to find entry in persistent state store for key: " + key, e);
        }

        return (document != null) ? getStoreEntryFromDocument(key, document) : null;
    }

    private StoreEntry getStoreEntryFromDocument(String key, Document document) {
        final Element rootElement = document.getRootElement();

        final String value = rootElement.element("value").getStringValue();
        final boolean isInitialEntry = new Boolean(rootElement.element("is-initial-entry").getStringValue()).booleanValue();
        final Map sessionIdsMap = new HashMap();
        {
            final List sessionIdsList = rootElement.elements("session-id");
            for (Iterator i = sessionIdsList.iterator(); i.hasNext();) {
                final Element currentElement = (Element) i.next();
                final String currentSessionId = currentElement.getStringValue();
                sessionIdsMap.put(currentSessionId, "");
            }
        }

        return new StoreEntry(key, value, isInitialEntry, sessionIdsMap);
    }

    private static class XMLDBAccessor extends XMLDBProcessor {

        public void query(PipelineContext pipelineContext, Datasource datasource, String collectionName, boolean createCollection, String resourceId, String query, Map namespaceContext, ContentHandler contentHandler) {
            super.query(pipelineContext, datasource, collectionName, createCollection, resourceId, query, namespaceContext, contentHandler);
        }

        protected Document getResource(PipelineContext pipelineContext, Datasource datasource, String collectionName, boolean createCollection, String resourceName) {

            ensureDriverRegistered(pipelineContext, datasource);
            try {
                Collection collection = getCollection(pipelineContext, datasource, collectionName);
                if (collection == null) {
                    if (!createCollection)
                        throw new OXFException("Cannot find collection '" + collectionName + "'.");
                    else
                        collection = createCollection(pipelineContext, datasource, collectionName);
                }
                final Resource resource = collection.getResource(resourceName);
                if (resource == null) {
                    return null;
                } else if (resource instanceof XMLResource) {

                    final LocationDocumentResult documentResult = new LocationDocumentResult();
                    final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
                    identity.setResult(documentResult);

                    ((XMLResource) resource).getContentAsSAX(new DatabaseReadContentHandler(identity));

                    return documentResult.getDocument();
                } else {
                    throw new OXFException("Unsupported resource type: " + resource.getClass());
                }
            } catch (XMLDBException e) {
                throw new OXFException(e);
            }
        }

        protected void storeResource(PipelineContext pipelineContext, Datasource datasource, String collectionName, boolean createCollection, String resourceName, String document) {
            super.storeResource(pipelineContext, datasource, collectionName, createCollection, resourceName, document);
        }
    }
}
