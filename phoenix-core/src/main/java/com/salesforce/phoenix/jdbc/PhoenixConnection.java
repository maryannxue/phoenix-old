/*******************************************************************************
 * Copyright (c) 2013, Salesforce.com, Inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 *     Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *     Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *     Neither the name of Salesforce.com nor the names of its contributors may 
 *     be used to endorse or promote products derived from this software without 
 *     specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE 
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR 
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, 
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE 
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 ******************************************************************************/
package com.salesforce.phoenix.jdbc;

import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.text.Format;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.phoenix.client.KeyValueBuilder;
import com.salesforce.phoenix.exception.SQLExceptionCode;
import com.salesforce.phoenix.exception.SQLExceptionInfo;
import com.salesforce.phoenix.execute.MutationState;
import com.salesforce.phoenix.jdbc.PhoenixStatement.PhoenixStatementParser;
import com.salesforce.phoenix.query.ConnectionQueryServices;
import com.salesforce.phoenix.query.DelegateConnectionQueryServices;
import com.salesforce.phoenix.query.MetaDataMutated;
import com.salesforce.phoenix.query.QueryConstants;
import com.salesforce.phoenix.query.QueryServices;
import com.salesforce.phoenix.query.QueryServicesOptions;
import com.salesforce.phoenix.schema.PArrayDataType;
import com.salesforce.phoenix.schema.PColumn;
import com.salesforce.phoenix.schema.PDataType;
import com.salesforce.phoenix.schema.PMetaData;
import com.salesforce.phoenix.schema.PMetaDataImpl;
import com.salesforce.phoenix.schema.PName;
import com.salesforce.phoenix.schema.PTable;
import com.salesforce.phoenix.util.DateUtil;
import com.salesforce.phoenix.util.JDBCUtil;
import com.salesforce.phoenix.util.PhoenixRuntime;
import com.salesforce.phoenix.util.ReadOnlyProps;
import com.salesforce.phoenix.util.SQLCloseable;
import com.salesforce.phoenix.util.SQLCloseables;


/**
 * 
 * JDBC Connection implementation of Phoenix.
 * Currently the following are supported:
 * - Statement
 * - PreparedStatement
 * The connection may only be used with the following options:
 * - ResultSet.TYPE_FORWARD_ONLY
 * - Connection.TRANSACTION_READ_COMMITTED
 * 
 * @author jtaylor
 * @since 0.1
 */
public class PhoenixConnection implements Connection, com.salesforce.phoenix.jdbc.Jdbc7Shim.Connection, MetaDataMutated  {
    private final String url;
    private final ConnectionQueryServices services;
    private final Properties info;
    private List<SQLCloseable> statements = new ArrayList<SQLCloseable>();
    private final Format[] formatters = new Format[PDataType.values().length];
    private final MutationState mutationState;
    private final int mutateBatchSize;
    private final Long scn;
    private boolean isAutoCommit = false;
    private PMetaData metaData;
    private final PName tenantId;
    private final String datePattern;
    
    private boolean isClosed = false;
    
    private static Properties newPropsWithSCN(long scn, Properties props) {
        props = new Properties(props);
        props.setProperty(PhoenixRuntime.CURRENT_SCN_ATTRIB, Long.toString(scn));
        return props;
    }
    
    public PhoenixConnection(PhoenixConnection connection) throws SQLException {
        this(connection.getQueryServices(), connection.getURL(), connection.getClientInfo(), connection.getPMetaData());
        this.isAutoCommit = connection.isAutoCommit;
    }
    
    public PhoenixConnection(PhoenixConnection connection, long scn) throws SQLException {
        this(connection.getQueryServices(), connection, scn);
    }
    
    public PhoenixConnection(ConnectionQueryServices services, PhoenixConnection connection, long scn) throws SQLException {
        this(services, connection.getURL(), newPropsWithSCN(scn,connection.getClientInfo()), PMetaDataImpl.pruneNewerTables(scn, connection.getPMetaData()));
        this.isAutoCommit = connection.isAutoCommit;
    }
    
    @SuppressWarnings("unchecked")
    public PhoenixConnection(ConnectionQueryServices services, String url, Properties info, PMetaData metaData) throws SQLException {
        this.url = url;
        // Copy so client cannot change
        this.info = info == null ? new Properties() : new Properties(info);
        if (this.info.isEmpty()) {
            this.services = services;
        } else {
            Map<String, String> existingProps = services.getProps().asMap();
            Map<String, String> tmpAugmentedProps = Maps.newHashMapWithExpectedSize(existingProps.size() + info.size());
            tmpAugmentedProps.putAll(existingProps);
            tmpAugmentedProps.putAll((Map)this.info);
            final ReadOnlyProps augmentedProps = new ReadOnlyProps(tmpAugmentedProps);
            this.services = new DelegateConnectionQueryServices(services) {
    
                @Override
                public ReadOnlyProps getProps() {
                    return augmentedProps;
                }
            };
        }
        this.scn = JDBCUtil.getCurrentSCN(url, this.info);
        this.tenantId = JDBCUtil.getTenantId(url, this.info);
        this.mutateBatchSize = JDBCUtil.getMutateBatchSize(url, this.info, services.getProps());
        datePattern = services.getProps().get(QueryServices.DATE_FORMAT_ATTRIB, DateUtil.DEFAULT_DATE_FORMAT);
        int maxSize = services.getProps().getInt(QueryServices.MAX_MUTATION_SIZE_ATTRIB,QueryServicesOptions.DEFAULT_MAX_MUTATION_SIZE);
        Format dateTimeFormat = DateUtil.getDateFormatter(datePattern);
        formatters[PDataType.DATE.ordinal()] = dateTimeFormat;
        formatters[PDataType.TIME.ordinal()] = dateTimeFormat;
        this.metaData = metaData;
        this.mutationState = new MutationState(maxSize, this);
        services.addConnection(this);
    }

    public int executeStatements(Reader reader, List<Object> binds, PrintStream out) throws IOException, SQLException {
        int bindsOffset = 0;
        int nStatements = 0;
        PhoenixStatementParser parser = new PhoenixStatementParser(reader);
        try {
            while (true) {
                PhoenixPreparedStatement stmt = new PhoenixPreparedStatement(this, parser);
                ParameterMetaData paramMetaData = stmt.getParameterMetaData();
                for (int i = 0; i < paramMetaData.getParameterCount(); i++) {
                    stmt.setObject(i+1, binds.get(bindsOffset+i));
                }
                long start = System.currentTimeMillis();
                boolean isQuery = stmt.execute();
                if (isQuery) {
                    ResultSet rs = stmt.getResultSet();
                    if (!rs.next()) {
                        if (out != null) {
                            out.println("no rows selected");
                        }
                    } else {
                        int columnCount = 0;
                        if (out != null) {
                            ResultSetMetaData md = rs.getMetaData();
                            columnCount = md.getColumnCount();
                            for (int i = 1; i <= columnCount; i++) {
                                int displayWidth = md.getColumnDisplaySize(i);
                                String label = md.getColumnLabel(i);
                                if (md.isSigned(i)) {
                                    out.print(displayWidth < label.length() ? label.substring(0,displayWidth) : Strings.padStart(label, displayWidth, ' '));
                                    out.print(' ');
                                } else {
                                    out.print(displayWidth < label.length() ? label.substring(0,displayWidth) : Strings.padEnd(md.getColumnLabel(i), displayWidth, ' '));
                                    out.print(' ');
                                }
                            }
                            out.println();
                            for (int i = 1; i <= columnCount; i++) {
                                int displayWidth = md.getColumnDisplaySize(i);
                                out.print(Strings.padStart("", displayWidth,'-'));
                                out.print(' ');
                            }
                            out.println();
                        }
                        do {
                            if (out != null) {
                                ResultSetMetaData md = rs.getMetaData();
                                for (int i = 1; i <= columnCount; i++) {
                                    int displayWidth = md.getColumnDisplaySize(i);
                                    String value = rs.getString(i);
                                    String valueString = value == null ? QueryConstants.NULL_DISPLAY_TEXT : value;
                                    if (md.isSigned(i)) {
                                        out.print(Strings.padStart(valueString, displayWidth, ' '));
                                    } else {
                                        out.print(Strings.padEnd(valueString, displayWidth, ' '));
                                    }
                                    out.print(' ');
                                }
                                out.println();
                            }
                        } while (rs.next());
                    }
                } else if (out != null){
                    int updateCount = stmt.getUpdateCount();
                    if (updateCount >= 0) {
                        out.println((updateCount == 0 ? "no" : updateCount) + (updateCount == 1 ? " row " : " rows ") + stmt.getUpdateOperation().toString());
                    }
                }
                bindsOffset += paramMetaData.getParameterCount();
                double elapsedDuration = ((System.currentTimeMillis() - start) / 1000.0);
                out.println("Time: " + elapsedDuration + " sec(s)\n");
                nStatements++;
            }
        } catch (EOFException e) {
        }
        return nStatements;
    }

    public @Nullable PName getTenantId() {
        return tenantId;
    }
    
    public Long getSCN() {
        return scn;
    }
    
    public int getMutateBatchSize() {
        return mutateBatchSize;
    }
    
    public PMetaData getPMetaData() {
        return metaData;
    }

    public MutationState getMutationState() {
        return mutationState;
    }
    
    public String getDatePattern() {
        return datePattern;
    }
    
    public Format getFormatter(PDataType type) {
        return formatters[type.ordinal()];
    }
    
    public String getURL() {
        return url;
    }
    
    public ConnectionQueryServices getQueryServices() {
        return services;
    }
    
    @Override
    public void clearWarnings() throws SQLException {
    }

    private void closeStatements() throws SQLException {
        List<SQLCloseable> statements = this.statements;
        // create new list to prevent close of statements
        // from modifying this list.
        this.statements = Lists.newArrayList();
        try {
            mutationState.rollback(this);
        } finally {
            try {
                SQLCloseables.closeAll(statements);
            } finally {
                statements.clear();
            }
        }
    }
    
    @Override
    public void close() throws SQLException {
        if (isClosed) {
            return;
        }
        try {
            try {
                closeStatements();
            } finally {
                services.removeConnection(this);
            }
        } finally {
            isClosed = true;
        }
    }

    @Override
    public void commit() throws SQLException {
        mutationState.commit();
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    	PDataType arrayPrimitiveType = PDataType.fromSqlTypeName(typeName);
    	return PArrayDataType.instantiatePhoenixArray(arrayPrimitiveType, elements);
    }

    @Override
    public Blob createBlob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Clob createClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public NClob createNClob() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Statement createStatement() throws SQLException {
        PhoenixStatement statement = new PhoenixStatement(this);
        statements.add(statement);
        return statement;
    }

    /**
     * Back-door way to inject processing into walking through a result set
     * @param statementFactory
     * @return PhoenixStatement
     * @throws SQLException
     */
    public PhoenixStatement createStatement(PhoenixStatementFactory statementFactory) throws SQLException {
        PhoenixStatement statement = statementFactory.newStatement(this);
        statements.add(statement);
        return statement;
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException();
        }
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLFeatureNotSupportedException();
        }
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return isAutoCommit;
    }

    @Override
    public String getCatalog() throws SQLException {
        return "";
    }

    @Override
    public Properties getClientInfo() throws SQLException { 
        // Defensive copy so client cannot change
        return new Properties(info);
    }

    @Override
    public String getClientInfo(String name) {
        return info.getProperty(name);
    }

    @Override
    public int getHoldability() throws SQLException {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return new PhoenixDatabaseMetaData(this);
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        return Connection.TRANSACTION_READ_COMMITTED;
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        return Collections.emptyMap();
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        return null;
    }

    @Override
    public boolean isClosed() throws SQLException {
        return isClosed;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return true;
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        // TODO: run query here or ping
        return !isClosed;
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        PhoenixPreparedStatement statement = new PhoenixPreparedStatement(this, sql);
        statements.add(statement);
        return statement;
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY || resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw new SQLFeatureNotSupportedException();
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
            int resultSetHoldability) throws SQLException {
        if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLFeatureNotSupportedException();
        }
        return prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void rollback() throws SQLException {
        mutationState.rollback(this);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setAutoCommit(boolean isAutoCommit) throws SQLException {
        this.isAutoCommit = isAutoCommit;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        if (readOnly) {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        if (level != Connection.TRANSACTION_READ_COMMITTED) {
            throw new SQLFeatureNotSupportedException();
        }
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (!iface.isInstance(this)) {
            throw new SQLExceptionInfo.Builder(SQLExceptionCode.CLASS_NOT_UNWRAPPABLE)
                .setMessage(this.getClass().getName() + " not unwrappable from " + iface.getName())
                .build().buildException();
        }
        return (T)this;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public String getSchema() throws SQLException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        // TODO Auto-generated method stub
        
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public PMetaData addTable(PTable table) throws SQLException {
        // TODO: since a connection is only used by one thread at a time,
        // we could modify this metadata in place since it's not shared.
        if (scn == null || scn > table.getTimeStamp()) {
            metaData = metaData.addTable(table);
        }
        //Cascade through to connectionQueryServices too
        getQueryServices().addTable(table);
        return metaData;
    }

    @Override
    public PMetaData addColumn(String tableName, List<PColumn> columns, long tableTimeStamp, long tableSeqNum, boolean isImmutableRows)
            throws SQLException {
        metaData = metaData.addColumn(tableName, columns, tableTimeStamp, tableSeqNum, isImmutableRows);
        //Cascade through to connectionQueryServices too
        getQueryServices().addColumn(tableName, columns, tableTimeStamp, tableSeqNum, isImmutableRows);
        return metaData;
    }

    @Override
    public PMetaData removeTable(String tableName) throws SQLException {
        metaData = metaData.removeTable(tableName);
        //Cascade through to connectionQueryServices too
        getQueryServices().removeTable(tableName);
        return metaData;
    }

    @Override
    public PMetaData removeColumn(String tableName, String familyName, String columnName, long tableTimeStamp,
            long tableSeqNum) throws SQLException {
        metaData = metaData.removeColumn(tableName, familyName, columnName, tableTimeStamp, tableSeqNum);
        //Cascade through to connectionQueryServices too
        getQueryServices().removeColumn(tableName, familyName, columnName, tableTimeStamp, tableSeqNum);
        return metaData;
    }

    protected boolean removeStatement(PhoenixStatement statement) throws SQLException {
        return statements.remove(statement);
   }

    public KeyValueBuilder getKeyValueBuilder() {
        return this.services.getKeyValueBuilder();
    }
}