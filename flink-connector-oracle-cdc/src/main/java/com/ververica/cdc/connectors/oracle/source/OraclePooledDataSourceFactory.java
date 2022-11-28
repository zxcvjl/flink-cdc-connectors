/*
 * Copyright 2022 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.oracle.source;

import com.ververica.cdc.connectors.base.config.JdbcSourceConfig;
import com.ververica.cdc.connectors.base.relational.connection.JdbcConnectionPoolFactory;
import com.ververica.cdc.connectors.oracle.source.config.OracleSourceConfig;
import org.apache.commons.lang3.StringUtils;

/** A Oracle datasource factory. */
public class OraclePooledDataSourceFactory extends JdbcConnectionPoolFactory {

    public static final String JDBC_URL_PATTERN = "jdbc:oracle:thin:@%s:%s:%s";

    @Override
    public String getJdbcUrl(JdbcSourceConfig sourceConfig) {
        OracleSourceConfig oracleSourceConfig = (OracleSourceConfig) sourceConfig;
        if (StringUtils.isNotBlank(oracleSourceConfig.getUrl())) {
            return oracleSourceConfig.getUrl();
        } else {
            String hostName = sourceConfig.getHostname();
            int port = sourceConfig.getPort();
            String database = sourceConfig.getDatabaseList().get(0);
            return String.format(JDBC_URL_PATTERN, hostName, port, database);
        }
    }
}
