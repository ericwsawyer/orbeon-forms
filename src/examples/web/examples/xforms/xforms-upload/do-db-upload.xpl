<!--
    Copyright (C) 2004 Orbeon, Inc.

    This program is free software; you can redistribute it and/or modify it under the terms of the
    GNU Lesser General Public License as published by the Free Software Foundation; either version
    2.1 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Lesser General Public License for more details.

    The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
-->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
          xmlns:delegation="http://orbeon.org/oxf/xml/delegation"
          xmlns:sql="http://orbeon.org/oxf/xml/sql"
          xmlns:oxf="http://www.orbeon.com/oxf/processors">

    <p:param name="instance" type="input"/>
    <p:param name="data" type="output"/>

    <!-- Insert the data into the database -->
    <!-- We do not need to dereference the URI, as the SQL processor understands xs:anyURI -->
    <p:processor name="oxf:sql">
        <p:input name="data" href="#instance"/>
        <p:input name="config">
            <sql:config>
                <sql:connection>
                    <sql:datasource>db</sql:datasource>
                    <urls>
                        <url>
                            <sql:execute>
                                <sql:update>
                                    delete from test_blob
                                </sql:update>
                            </sql:execute>
                            <sql:execute>
                                <sql:update select="/*/files/file[. != '']">
                                    insert into test_blob (blob_column)
                                    values (<sql:param select="." type="xs:anyURI"/>)
                                </sql:update>
                            </sql:execute>
                            <sql:text>xforms-uploaded-image</sql:text>
                        </url>
                    </urls>
                </sql:connection>
            </sql:config>
        </p:input>
        <p:output name="output" ref="data"/>
    </p:processor>

</p:config>
