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
package com.salesforce.phoenix.end2end.index;

import static com.salesforce.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;

import org.apache.hadoop.hbase.HTableDescriptor;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.Maps;
import com.salesforce.phoenix.query.QueryServices;
import com.salesforce.phoenix.util.QueryUtil;
import com.salesforce.phoenix.util.ReadOnlyProps;

public class MutableIndexTest extends BaseMutableIndexTest {
    @BeforeClass 
    public static void doSetup() throws Exception {
        Map<String,String> props = Maps.newHashMapWithExpectedSize(1);
        // Don't split intra region so we can more easily know that the n-way parallelization is for the explain plan
        props.put(QueryServices.MAX_INTRA_REGION_PARALLELIZATION_ATTRIB, Integer.toString(1));
        // Forces server cache to be used
        props.put(QueryServices.INDEX_MUTATE_BATCH_SIZE_THRESHOLD_ATTRIB, Integer.toString(2));
        // Must update config before starting server
        startServer(getUrl(), new ReadOnlyProps(props.entrySet().iterator()));
    }
    
    @Test
    public void testIndexWithNullableFixedWithCols() throws Exception {
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        try {
            createTestTable();
            populateTestTable();
            String ddl = "CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME
                    + " (char_col1 ASC, int_col1 ASC)"
                    + " INCLUDE (long_col1, long_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            
            String query = "SELECT d.char_col1, int_col1 from " + DATA_TABLE_FULL_NAME + " as d";
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals("chara", rs.getString("char_col1"));
            assertEquals(2, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertFalse(rs.next());
        } finally {
            conn.close();
        }
    }

    @Test
    public void testIndexWithNullableDateCol() throws Exception {
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        try {
            Date date = new Date(System.currentTimeMillis());
            
            createTestTable();
            populateTestTable(date);
            String ddl = "CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (date_col)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            
            String query = "SELECT int_pk from " + DATA_TABLE_FULL_NAME ;
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1));
            assertFalse(rs.next());
        } finally {
            conn.close();
        }
    }

    @Test
    public void testCoveredColumnUpdates() throws Exception {
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        try {
            createTestTable();
            populateTestTable();
            String ddl = "CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME
                    + " (char_col1 ASC, int_col1 ASC)"
                    + " INCLUDE (long_col1, long_col2)";
            PreparedStatement stmt = conn.prepareStatement(ddl);
            stmt.execute();
            
            String query = "SELECT char_col1, int_col1, long_col2 from " + DATA_TABLE_FULL_NAME;
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(3L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(4L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            
            stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME
                    + "(varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2) SELECT varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2*2 FROM "
                    + DATA_TABLE_FULL_NAME + " WHERE long_col2=?");
            stmt.setLong(1,4L);
            assertEquals(1,stmt.executeUpdate());
            conn.commit();

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(3L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(8L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            
            stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME
                    + "(varchar_pk, char_pk, int_pk, long_pk , decimal_pk, long_col2) SELECT varchar_pk, char_pk, int_pk, long_pk , decimal_pk, null FROM "
                    + DATA_TABLE_FULL_NAME + " WHERE long_col2=?");
            stmt.setLong(1,3L);
            assertEquals(1,stmt.executeUpdate());
            conn.commit();
            
            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(2, rs.getInt(2));
            assertEquals(0, rs.getLong(3));
            assertTrue(rs.wasNull());
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(3, rs.getInt(2));
            assertEquals(8L, rs.getLong(3));
            assertTrue(rs.next());
            assertEquals("chara", rs.getString(1));
            assertEquals(4, rs.getInt(2));
            assertEquals(5L, rs.getLong(3));
            assertFalse(rs.next());
            
        } finally {
            conn.close();
        }
    }
    
    @Test
    public void testSelectAllAndAliasWithIndex() throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        conn.createStatement().execute("CREATE TABLE " + DATA_TABLE_FULL_NAME + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        
        conn.createStatement().execute("CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v2 DESC) INCLUDE (v1)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        stmt.setString(1,"b");
        stmt.setString(2, "y");
        stmt.setString(3, "2");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));

        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("b",rs.getString(1));
        assertEquals("y",rs.getString(2));
        assertEquals("2",rs.getString(3));
        assertEquals("b",rs.getString("k"));
        assertEquals("y",rs.getString("v1"));
        assertEquals("2",rs.getString("v2"));
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertEquals("1",rs.getString(3));
        assertEquals("a",rs.getString("k"));
        assertEquals("x",rs.getString("v1"));
        assertEquals("1",rs.getString("v2"));
        assertFalse(rs.next());
        
        query = "SELECT v1 as foo FROM " + DATA_TABLE_FULL_NAME + " WHERE v2 = '1' ORDER BY foo";
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER " +INDEX_TABLE_FULL_NAME + " [~'1']\n" + 
                "    SERVER TOP -1 ROWS SORTED BY [V1]\n" + 
                "CLIENT MERGE SORT", QueryUtil.getExplainPlan(rs));

        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("x",rs.getString("foo"));
        assertFalse(rs.next());
    }
    
    @Test
    public void testSelectCF() throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        conn.createStatement().execute("CREATE TABLE " + DATA_TABLE_FULL_NAME + " (k VARCHAR NOT NULL PRIMARY KEY, a.v1 VARCHAR, a.v2 VARCHAR, b.v1 VARCHAR)  ");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        
        conn.createStatement().execute("CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v2 DESC) INCLUDE (a.v1)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.setString(4, "A");
        stmt.execute();
        stmt.setString(1,"b");
        stmt.setString(2, "y");
        stmt.setString(3, "2");
        stmt.setString(4, "B");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + DATA_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));

        query = "SELECT a.* FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("y",rs.getString(1));
        assertEquals("2",rs.getString(2));
        assertEquals("y",rs.getString("v1"));
        assertEquals("2",rs.getString("v2"));
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("1",rs.getString(2));
        assertEquals("x",rs.getString("v1"));
        assertEquals("1",rs.getString("v2"));
        assertFalse(rs.next());
    }
    
    @Test
    public void testCoveredColumns() throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        conn.createStatement().execute("CREATE TABLE " + DATA_TABLE_FULL_NAME + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        
        conn.createStatement().execute("CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v1) INCLUDE (v2)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("a",rs.getString(2));
        assertEquals("1",rs.getString(3));
        assertFalse(rs.next());

        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + "(k,v2) VALUES(?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, null);
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("a",rs.getString(2));
        assertNull(rs.getString(3));
        assertFalse(rs.next());

        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));

        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertNull(rs.getString(3));
        assertFalse(rs.next());

        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + "(k,v2) VALUES(?,?)");
        stmt.setString(1,"a");
        stmt.setString(2,"3");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));

        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertEquals("3",rs.getString(3));
        assertFalse(rs.next());

        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + "(k,v2) VALUES(?,?)");
        stmt.setString(1,"a");
        stmt.setString(2,"4");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));

        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("x",rs.getString(2));
        assertEquals("4",rs.getString(3));
        assertFalse(rs.next());
    }

    @Test
    public void testCompoundIndexKey() throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);

        // make sure that the tables are empty, but reachable
        conn.createStatement().execute("CREATE TABLE " + DATA_TABLE_FULL_NAME + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
        
        conn.createStatement().execute("CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v1, v2)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());

        // load some data into the table
        PreparedStatement stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x",rs.getString(1));
        assertEquals("1",rs.getString(2));
        assertEquals("a",rs.getString(3));
        assertFalse(rs.next());

        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1,"a");
        stmt.setString(2, "y");
        stmt.setString(3, null);
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("y",rs.getString(1));
        assertNull(rs.getString(2));
        assertEquals("a",rs.getString(3));
        assertFalse(rs.next());

        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME, QueryUtil.getExplainPlan(rs));
        //make sure the data table looks like what we expect
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a",rs.getString(1));
        assertEquals("y",rs.getString(2));
        assertNull(rs.getString(3));
        assertFalse(rs.next());
        
        // Upsert new row with null leading index column
        stmt.setString(1,"b");
        stmt.setString(2, null);
        stmt.setString(3, "3");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals(null,rs.getString(1));
        assertEquals("3",rs.getString(2));
        assertEquals("b",rs.getString(3));
        assertTrue(rs.next());
        assertEquals("y",rs.getString(1));
        assertNull(rs.getString(2));
        assertEquals("a",rs.getString(3));
        assertFalse(rs.next());

        // Update row with null leading index column to have a value
        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?)");
        stmt.setString(1,"b");
        stmt.setString(2, "z");
        stmt.execute();
        conn.commit();
        
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("y",rs.getString(1));
        assertNull(rs.getString(2));
        assertEquals("a",rs.getString(3));
        assertTrue(rs.next());
        assertEquals("z",rs.getString(1));
        assertEquals("3",rs.getString(2));
        assertEquals("b",rs.getString(3));
        assertFalse(rs.next());

    }
 
    /**
     * There was a case where if there were multiple updates to a single row in the same batch, the
     * index wouldn't be updated correctly as each element of the batch was evaluated with the state
     * previous to the batch, rather than with the rest of the batch. This meant you could do a put
     * and a delete on a row in the same batch and the index result would contain the current + put
     * and current + delete, but not current + put + delete.
     * @throws Exception on failure
     */
    @Test
    public void testMultipleUpdatesToSingleRow() throws Exception {
        String query;
        ResultSet rs;
    
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
    
        // make sure that the tables are empty, but reachable
        conn.createStatement().execute(
          "CREATE TABLE " + DATA_TABLE_FULL_NAME
              + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR)");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
    
        conn.createStatement().execute(
          "CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v1, v2)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
    
        // load some data into the table
        PreparedStatement stmt =
            conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        conn.commit();
        
        // make sure the index is working as expected
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x", rs.getString(1));
        assertEquals("1", rs.getString(2));
        assertEquals("a", rs.getString(3));
        assertFalse(rs.next());

        // do multiple updates to the same row, in the same batch
        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + "(k, v1) VALUES(?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, "y");
        stmt.execute();
        stmt = conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + "(k,v2) VALUES(?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, null);
        stmt.execute();
        conn.commit();
    
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("y", rs.getString(1));
        assertNull(rs.getString(2));
        assertEquals("a", rs.getString(3));
        assertFalse(rs.next());
    
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME,
          QueryUtil.getExplainPlan(rs));
    
        // check that the data table matches as expected
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a", rs.getString(1));
        assertEquals("y", rs.getString(2));
        assertNull(rs.getString(3));
        assertFalse(rs.next());
    }

    @Test
    public void testMultipleUpdatesAcrossRegions() throws Exception {
        String query;
        ResultSet rs;
    
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
    
        // make sure that the tables are empty, but reachable
        conn.createStatement().execute(
          "CREATE TABLE " + DATA_TABLE_FULL_NAME
              + " (k VARCHAR NOT NULL PRIMARY KEY, v1 VARCHAR, v2 VARCHAR) " + HTableDescriptor.MAX_FILESIZE + "=1, " + HTableDescriptor.MEMSTORE_FLUSHSIZE + "=1 " +
                  "SPLIT ON ('b')");
        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
    
        conn.createStatement().execute(
          "CREATE INDEX " + INDEX_TABLE_NAME + " ON " + DATA_TABLE_FULL_NAME + " (v1, v2)");
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertFalse(rs.next());
    
        // load some data into the table
        PreparedStatement stmt =
            conn.prepareStatement("UPSERT INTO " + DATA_TABLE_FULL_NAME + " VALUES(?,?,?)");
        stmt.setString(1, "a");
        stmt.setString(2, "x");
        stmt.setString(3, "1");
        stmt.execute();
        stmt.setString(1, "b");
        stmt.setString(2, "y");
        stmt.setString(3, "2");
        stmt.execute();
        stmt.setString(1, "c");
        stmt.setString(2, "z");
        stmt.setString(3, "3");
        stmt.execute();
        conn.commit();
        
        // make sure the index is working as expected
        query = "SELECT * FROM " + INDEX_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("x", rs.getString(1));
        assertEquals("1", rs.getString(2));
        assertEquals("a", rs.getString(3));
        assertTrue(rs.next());
        assertEquals("y", rs.getString(1));
        assertEquals("2", rs.getString(2));
        assertEquals("b", rs.getString(3));
        assertTrue(rs.next());
        assertEquals("z", rs.getString(1));
        assertEquals("3", rs.getString(2));
        assertEquals("c", rs.getString(3));
        assertFalse(rs.next());

        query = "SELECT * FROM " + DATA_TABLE_FULL_NAME;
        rs = conn.createStatement().executeQuery("EXPLAIN " + query);
        assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER " + INDEX_TABLE_FULL_NAME,
          QueryUtil.getExplainPlan(rs));
    
        // check that the data table matches as expected
        rs = conn.createStatement().executeQuery(query);
        assertTrue(rs.next());
        assertEquals("a", rs.getString(1));
        assertEquals("x", rs.getString(2));
        assertEquals("1", rs.getString(3));
        assertTrue(rs.next());
        assertEquals("b", rs.getString(1));
        assertEquals("y", rs.getString(2));
        assertEquals("2", rs.getString(3));
        assertTrue(rs.next());
        assertEquals("c", rs.getString(1));
        assertEquals("z", rs.getString(2));
        assertEquals("3", rs.getString(3));
        assertFalse(rs.next());
    }
    
    @Test
    public void testIndexWithCaseSensitiveCols() throws Exception {
        String query;
        ResultSet rs;
        
        Properties props = new Properties(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        conn.setAutoCommit(false);
        try {
            conn.createStatement().execute("CREATE TABLE cs (k VARCHAR NOT NULL PRIMARY KEY, \"V1\" VARCHAR, \"v2\" VARCHAR)");
            query = "SELECT * FROM cs";
            rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());

            conn.createStatement().execute("CREATE INDEX ics ON cs (\"v2\") INCLUDE (\"V1\")");
            query = "SELECT * FROM ics";
            rs = conn.createStatement().executeQuery(query);
            assertFalse(rs.next());

            PreparedStatement stmt = conn.prepareStatement("UPSERT INTO cs VALUES(?,?,?)");
            stmt.setString(1,"a");
            stmt.setString(2, "x");
            stmt.setString(3, "1");
            stmt.execute();
            stmt.setString(1,"b");
            stmt.setString(2, "y");
            stmt.setString(3, "2");
            stmt.execute();
            conn.commit();

            query = "SELECT * FROM cs WHERE \"v2\" = '1'";
            rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals("CLIENT PARALLEL 1-WAY RANGE SCAN OVER ICS ['1']", QueryUtil.getExplainPlan(rs));

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("a",rs.getString(1));
            assertEquals("x",rs.getString(2));
            assertEquals("1",rs.getString(3));
            assertEquals("a",rs.getString("k"));
            assertEquals("x",rs.getString("V1"));
            assertEquals("1",rs.getString("v2"));
            assertFalse(rs.next());

            query = "SELECT \"V1\", \"V1\" as foo1, \"v2\" as foo, \"v2\" as \"Foo1\", \"v2\" FROM cs ORDER BY foo";
            rs = conn.createStatement().executeQuery("EXPLAIN " + query);
            assertEquals("CLIENT PARALLEL 1-WAY FULL SCAN OVER ICS", QueryUtil.getExplainPlan(rs));

            rs = conn.createStatement().executeQuery(query);
            assertTrue(rs.next());
            assertEquals("x",rs.getString(1));
            assertEquals("x",rs.getString("V1"));
            assertEquals("x",rs.getString(2));
            assertEquals("x",rs.getString("foo1"));
            assertEquals("1",rs.getString(3));
            assertEquals("1",rs.getString("Foo"));
            assertEquals("1",rs.getString(4));
            assertEquals("1",rs.getString("Foo1"));
            assertEquals("1",rs.getString(5));
            assertEquals("1",rs.getString("v2"));
            assertTrue(rs.next());
            assertEquals("y",rs.getString(1));
            assertEquals("y",rs.getString("V1"));
            assertEquals("y",rs.getString(2));
            assertEquals("y",rs.getString("foo1"));
            assertEquals("2",rs.getString(3));
            assertEquals("2",rs.getString("Foo"));
            assertEquals("2",rs.getString(4));
            assertEquals("2",rs.getString("Foo1"));
            assertEquals("2",rs.getString(5));
            assertEquals("2",rs.getString("v2"));
            assertFalse(rs.next());
        } finally {
            conn.close();
        }
    }
}