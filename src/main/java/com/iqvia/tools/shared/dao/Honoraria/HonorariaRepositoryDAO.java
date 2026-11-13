package com.iqvia.tools.shared.dao.Honoraria;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;


import javax.persistence.Query;
import javax.sql.DataSource;

import org.jboss.jca.adapters.jdbc.WrappedConnection;
import org.jboss.logging.Logger;
import org.springframework.stereotype.Repository;

import com.iqvia.tools.shared.dao.AbstractJpaDAOAction;
import com.iqvia.tools.shared.model.AccountingSchema.AdpStaleCheckFileDetail;
import com.iqvia.tools.shared.model.AccountingSchema.AdpStaleCheckFiles;
import com.iqvia.tools.shared.model.AccountingSchema.CheckRepository;
import com.iqvia.tools.shared.model.AccountingSchema.HonorariaRepository;
import com.iqvia.tools.shared.model.AccountingSchema.HonorariaSearchExceptionRow;
import com.iqvia.tools.shared.model.AccountingSchema.HonorariaSearchResult;
import com.iqvia.tools.shared.model.AccountingSchema.HonorariaSearchSummaryRow;
import com.iqvia.tools.shared.model.AccountingSchema.IncompleteFulfillmentBatchRow;
import com.iqvia.tools.shared.model.AccountingSchema.OpenIncompleteCurResult;
import com.iqvia.tools.shared.model.EventSchema.ActivityHonoraria;
import com.iqvia.tools.shared.model.EventSchema.Cells;

import oracle.jdbc.OracleConnection;
import oracle.jdbc.OracleTypes;



@Repository
public class HonorariaRepositoryDAO extends AbstractJpaDAOAction<HonorariaRepository>{
	private static final Logger logger = Logger.getLogger(HonorariaRepositoryDAO.class);

	/**
	 * PL/SQL {@code Number10Array} typename as resolved by JDBC — use {@code ACCOUNTING.NUMBER10ARRAY}
	 * instead if that is how the type is defined in your database (synonym to {@code TRXADMIN.NUMBER10ARRAY} is common).
	 */
	private static final String ORACLE_NUMBER10_ARRAY_TYPENAME = "TRXADMIN.NUMBER10ARRAY";

	public HonorariaRepositoryDAO() {
		setClazz(HonorariaRepository.class);
	}

	public List<HonorariaRepository> getHonoraria(String dispCode) {
		String sql = "FROM HonorariaRepository WHERE  dispositionCode = :dispCode";	
		Query query = entityManager.createQuery(sql);
		query.setParameter("dispCode", dispCode);

		List<HonorariaRepository> results = (List<HonorariaRepository>)query.getResultList();
			
		return results;
		
	}
	
	public List<Cells> getCells(long targetrxId, long pid) {
		String sql = "FROM Cells WHERE  targetrxId = :targetrxId and programEventId = :programEventId";	
		Query query = entityManager.createQuery(sql);
		query.setParameter("targetrxId", targetrxId);
		query.setParameter("programEventId", pid);

		List<Cells> results = (List<Cells>)query.getResultList();
			
		return results;
		
	}

	public List<Integer> updateHonorariaApprovals(String approved, String rejected, String user) {
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		PreparedStatement ps = null;
		int retValApp = 0;
		int retValRej = 0;
		ArrayList<Integer> retVals = new ArrayList();

		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			
			if (approved.length() > 0) {
				String SQL = "update HONORARIA_REPOSITORY set DISPOSITION_CODE = 'UNPR', UPDATED_USER = '"+user+"' where HONORARIA_REPOSITORY_ID in ("+approved+")";
				logger.debug(SQL);
				ps = wrappedConn.prepareStatement(SQL);
				retValApp = ps.executeUpdate();
				ps.close();
				retVals.add(retValApp);
			}

			if (rejected.length() > 0) {
				String SQL2 = "update HONORARIA_REPOSITORY set DISPOSITION_CODE = 'DELT', UPDATED_USER = '"+user+"' where HONORARIA_REPOSITORY_ID in ("+rejected+")";
				logger.debug(SQL2);
				ps = wrappedConn.prepareStatement(SQL2);
				retValRej = ps.executeUpdate();
				retVals.add(retValRej);
			}
			
		} catch (Exception e) {
			logger.error("updateHonorariaApprovals#SQL Exception.", e);
			e.printStackTrace();
		} finally {
			try {
				if (ps != null)
					ps.close();
				if (wrappedConn != null)
					wrappedConn.close();
			} catch (SQLException se) {
				se.printStackTrace(System.out);
			}
		}
		return retVals;
	}

	//////////////////////////////////////////////////////
	
	 public List<String> old_getUnprocessedHonoraria(String ffilter, String pfilter, String end_date) {
		List<String> out = new ArrayList<>();
		
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		
		int total_fulfillment = 0;
		int total_amt = 0;
		
		try {
			Date inDate = new SimpleDateFormat("MM/dd/yyyy").parse(end_date);
			java.sql.Date sqlDate = new java.sql.Date(inDate.getTime());
			logger.info("getUnprocessedHonoraria called with ffilter: " + ffilter + ", pfilter: " + pfilter + ", end_date: " + end_date);
		       
			wrappedConn = (WrappedConnection) ds.getConnection();
		
			logger.info("getUnprocessedHonoraria: Starting execution of ACCOUNTING.HonorariaRepositoryCur.searchUnprocessed call...");
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaRepositoryCur.searchUnprocessed(?,?,?,?,?,?,?)}");
			stmt.setString(1, ffilter);
			stmt.setString(2, pfilter);
			stmt.setDate(3, sqlDate);
			stmt.registerOutParameter(4, OracleTypes.NUMBER);
			stmt.registerOutParameter(5, OracleTypes.NUMBER);
			stmt.registerOutParameter(6, OracleTypes.NUMBER);
			stmt.registerOutParameter(7, OracleTypes.CURSOR);
			stmt.execute();
			
			total_fulfillment=stmt.getInt(4);
			total_amt=stmt.getInt(5);
			int ex = stmt.getInt(6);
		
			logger.info("getUnprocessedHonoraria: Total fulfillments=" + total_fulfillment +"; Total Amount=" + total_amt + ", ex=" + ex);
		
			if(ex==0) {
				out = new ArrayList<String>();
				out.add("noerr");
				out.add(String.valueOf(total_fulfillment));
				out.add(String.valueOf(total_amt));
			} else {
				ResultSet errRslt=(ResultSet) stmt.getObject(7);
				String err = "";
				if(errRslt != null) {    				
					while(errRslt.next()) {
						err += errRslt.getInt(1) + "~" + errRslt.getString(2) + "~" + errRslt.getString(3) + "\n";
						logger.debug(err);
					}
				}    			
				out = new ArrayList<String>();
				out.add("err");
				out.add(String.valueOf(total_fulfillment));
				out.add(String.valueOf(total_amt));
				out.add(err);
			}
			logger.info("Unprocessed data processed successfully.");	    	  	    	  		      	    		    	
		} catch (Exception e) {		
			logger.error("getUnprocessedHonoraria: "+e.getMessage());
		} finally {
			if(stmt != null){
				try{ stmt.close(); }catch(Exception ignore){};
			}
			if(wrappedConn != null){
				try{ wrappedConn.close();}catch(Exception ignore){};
			}
		
		}

		logger.info("getUnprocessedHonoraria: Size of out: " + out.size());  	    	  		      	    		    	
		return out;    	
	}
	 
	 
	 
	 public int old_generateFulfillmentBatch(String ffilter, String pfilter, String end_date, String inUser, int inTotalFulfillments, int inTotalAmt) {
    	int outBatchId = 0;
    	int outFulfillments = 0;
    	int outTotalAmt = 0;

		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;

    	try {
			Date inDate = new SimpleDateFormat("MM/dd/yyyy").parse(end_date);
			java.sql.Date sqlDate = new java.sql.Date(inDate.getTime());

    		logger.info("generateFulfillmentBatch: ffilter=" + ffilter + ", pfilter'=" + pfilter + ", end_date=" + end_date + ", inTotalFulfillments=" + inTotalFulfillments + ", inTotalAmt=" + inTotalAmt);	
    		
			wrappedConn = (WrappedConnection) ds.getConnection();

    		// Open a connection
    		logger.info("ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatch. ffilter=" + ffilter + ", pfilter=" + pfilter+ ", end_date=" + end_date + ", inTotalFulfillments=" + inTotalFulfillments+ ", inTotalAmt=" + inTotalAmt);
    		stmt=wrappedConn.prepareCall("{call ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatch(?,?,?,?,?,?,?)}");
    		stmt.setString(1, ffilter);
    		stmt.setString(2, pfilter);
    		stmt.setDate(3, sqlDate);
    		stmt.setString(4, inUser);
    		stmt.registerOutParameter(5, OracleTypes.NUMBER);
    		stmt.registerOutParameter(6, OracleTypes.NUMBER);
    		stmt.registerOutParameter(7, OracleTypes.NUMBER);
    		stmt.execute();
    		outFulfillments=stmt.getInt(5);
    		outTotalAmt=stmt.getInt(6);
    		outBatchId=stmt.getInt(7);
    		
    		if (outBatchId!=0) {
    			logger.info("generateFulfillmentBatch: NO Exceptions");

    			if (!(outFulfillments == inTotalFulfillments && outTotalAmt == inTotalAmt)) {
    				if (ffilter.equals("AMAZON")) { // If Amazon, ignore the fact that the counts/total amount may not match
    					logger.info("generateFulfillmentBatch: Amazon in/out mismatch - outBatchId'=" + outBatchId + ", Value of 'Fulfillments'=" + outFulfillments + ", Value of 'TotalAmt'=" + outTotalAmt);	
    				} else {
    					logger.info("generateFulfillmentBatch: Check/Other in/out mismatch - outBatchId=" + outBatchId + ", Value of 'Fulfillments'=" + outFulfillments + ", Value of 'TotalAmt'=" + outTotalAmt);	
        				outBatchId = 1;
    				}
    			}    			    		
    		} else {
    			logger.info("generateFulfillmentBatch: Exceptions: No valid batchId returned. Val of BatchId=" + outBatchId + ", Val of outFulfillments=" + outFulfillments + ", Val of outTotalAmt=" + outTotalAmt);
    		}

    		logger.info("generateFulfillmentBatch processed successfully.");	    	  	    	  		      	    		    	

		} catch (Exception e) {		
			logger.error("generateFulfillmentBatch: "+e.getMessage());
		} finally {
			if(stmt != null){
				try{ stmt.close(); }catch(Exception ignore){};
			}
			if(wrappedConn != null){
				try{ wrappedConn.close();}catch(Exception ignore){};
			}
		
		}
    		
   		logger.info("generateFulfillmentBatch: retVal = " + outBatchId);
    	
    	return outBatchId;
    }	 

	 
	 
   
	public List<CheckRepository> getChecksByNumber(String checkNo) {
		String sql = "FROM CheckRepository WHERE checkNo = :checkNo ORDER BY loadedDatetime DESC";	
		Query query = entityManager.createQuery(sql);
		query.setParameter("checkNo", checkNo);

		List<CheckRepository> results = (List<CheckRepository>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getChecksByNumber: No checks found for check number: " + checkNo);
		} else {
			logger.info("getChecksByNumber: Found " + results.size() + " checks for check number: " + checkNo);
		}
		
		return results;
	}
   
 

	public List<CheckRepository> getChecksByTargetrxId(Long trxid) {
		String sql = "FROM CheckRepository cr, HonorariaRepository hr, Fulfillment ff, FulfillmentDelivery fd "
				+ "WHERE hr.targetrxId = :trxid "
				+ "AND cr.fulfillmentDeliveryId = fd.fulfillmentDeliveryId "	
				+ "AND fd.fulfillmentId = ff.fulfillmentId "	
				+ "AND ff.fulfillmentId = hr.fulfillmentId "
				+ "ORDER BY cr.loadedDatetime DESC";
		Query query = entityManager.createQuery(sql);
		query.setParameter("trxid", trxid);

		List<CheckRepository> results = (List<CheckRepository>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getChecksByTargetrxId: No checks found for targetrxId: " + trxid);
		} else {
			logger.info("getChecksByTargetrxId: Found " + results.size() + " checks for targetrxId: " + trxid);
		}
		
		return results;
	}

	
	public List<CheckRepository> getChecksByDates(String strFromDate, String strToDate) { 
		Date fromDate;
		Date toDate;
		
		try {
			fromDate = new SimpleDateFormat("MM/dd/yyyy").parse(strFromDate);
			toDate = new SimpleDateFormat("MM/dd/yyyy").parse(strToDate);
		} catch (ParseException e) {
			logger.error("getChecksByDates: Invalid from_date format: " + strFromDate, e);
			return new ArrayList<>(); // Return empty list if date parsing fails			
		}

		String sql = "SELECT DISTINCT cr, hr.targetrxId "
				+ "FROM CheckRepository cr, HonorariaRepository hr, Fulfillment ff, FulfillmentDelivery fd "
	        	+ "WHERE cr.voidDatetime between :fromDate AND :toDate "
				+ "AND cr.fulfillmentDeliveryId = fd.fulfillmentDeliveryId "	
				+ "AND fd.fulfillmentId = ff.fulfillmentId "	
				+ "AND ff.fulfillmentId = hr.fulfillmentId "
				+ "ORDER BY cr.voidDatetime DESC";

		Query query = entityManager.createQuery(sql);
		query.setParameter("fromDate", new java.sql.Date(fromDate.getTime()));
		query.setParameter("toDate", new java.sql.Date(toDate.getTime()));

		List<CheckRepository> results = (List<CheckRepository>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getChecksByDates: No checks found for from_date: " + strFromDate);
		} else {
			logger.info("getChecksByDates: Found " + results.size() + " checks for from_date: " + strFromDate + " to_date: " + strToDate);
		}
		
		return results;
	}

	public List<AdpStaleCheckFiles> getStaleCheckFiles() {
		String sql = "FROM AdpStaleCheckFiles ORDER BY insDate DESC";	
		Query query = entityManager.createQuery(sql);

		List<AdpStaleCheckFiles> results = (List<AdpStaleCheckFiles>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getStaleCheckFiles: No stale check files found.");
		} else {
			logger.info("getStaleCheckFiles: Found " + results.size() + " stale check files.");
		}
		
		return results;
	}

	public List<AdpStaleCheckFileDetail> getStaleCheckFileDetails(int fileId) {
		String sql = "FROM AdpStaleCheckFileDetail WHERE fileId = :fileId ORDER BY checkNumber";	
		Query query = entityManager.createQuery(sql);
		query.setParameter("fileId", fileId);

		List<AdpStaleCheckFileDetail> results = (List<AdpStaleCheckFileDetail>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getStaleCheckFileDetails: No details found for fileId: " + fileId);
		} else {
			logger.info("getStaleCheckFileDetails: Found " + results.size() + " details for fileId: " + fileId);
		}
		
		return results;
	}
	
	public int loadChecks(ArrayList<String> checks, String fileName, String user) throws Exception{
		int batchId = -1;
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			logger.info("loadChecks: Starting execution of ADPStaleCheckFilesDML.addFile call with fileName: " + fileName + ", user: " + user);
			stmt = wrappedConn.prepareCall("{ call ADPStaleCheckFilesDML.addFile(?,?,?) }");
			stmt.registerOutParameter (1, OracleTypes.INTEGER);
			stmt.setString(2, fileName);
			stmt.setString(3, user);
			stmt.execute();

			batchId = stmt.getInt(1);
			
			// add the checks to the batch
			logger.info("loadChecks: Adding checks to batch with batchId: " + batchId);
			
		} catch (Exception e) {		
			logger.error("loadChecks: "+e.getMessage());
		} finally {
			if(stmt != null){
				try{ stmt.close(); }catch(Exception ignore){};
			}
			if(wrappedConn != null){
				try{ wrappedConn.close();}catch(Exception ignore){};
			}
		
		}

		return batchId;
	}



////////////////////////////////////////////////////////////////////////////////////////////////////////
/// New PL/SQL Procedures and Functions
/// 
	/**
	 * {@code ACCOUNTING.HonorariaRepositoryCur.searchUnprocessed}
	 */
	public HonorariaSearchResult searchUnprocessed(String fulfillmentMethod, String paymentTypeGroup, java.sql.Date inDueDate) {
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		ResultSet summaryRs = null;
		ResultSet exceptRs = null;
		HonorariaSearchResult result = new HonorariaSearchResult();
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaRepositoryCur.searchUnprocessed(?,?,?,?,?,?)}");
			stmt.setString(1, fulfillmentMethod);
			setNullableString(stmt, 2, paymentTypeGroup);
			stmt.setDate(3, inDueDate);
			stmt.registerOutParameter(4, OracleTypes.CURSOR);
			stmt.registerOutParameter(5, OracleTypes.NUMBER);
			stmt.registerOutParameter(6, OracleTypes.CURSOR);
			logger.info("searchUnprocessed: fulfillmentMethod=" + fulfillmentMethod + ", dueDate=" + inDueDate);
			stmt.execute();
			summaryRs = (ResultSet) stmt.getObject(4);
			readSummaryCursor(summaryRs, result);
			result.setExceptionCount(stmt.getInt(5));
			if (result.getExceptionCount() != 0) {
				exceptRs = (ResultSet) stmt.getObject(6);
				readExceptionCursor(exceptRs, result);
			}
		} catch (Exception e) {
			logger.error("searchUnprocessed: " + e.getMessage(), e);
		} finally {
			closeQuietly(summaryRs);
			closeQuietly(exceptRs);
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
		return result;
	}

	


	/**
	 * {@code ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatches}, then
	 * {@code ACCOUNTING.HonorariaRepositoryCur.searchEarned} on the same connection (same transaction).
	 * PROCEDURE generateFulfillmentBatches(
	 *     inFulfillmentMethod IN VARCHAR2,
	 *     inPaymentTypeGroup IN VARCHAR2,
	 *     inDueDate IN DATE,
	 *     inUser IN VARCHAR2 DEFAULT NULL,
	 *     outBatchIDs OUT Number10Array,
	 *     inBusinessLineIDs IN Number10Array,
	 *     inBatchCutoffAmounts IN Number10Array)
	 */
	public HonorariaSearchResult generateFulfillmentBatches(
			String fulfillmentMethod,
			String paymentTypeGroup,
			java.sql.Date inDueDate,
			String inUser,
			long[] inBusinessLineIds,
			Long[] inBatchCutoffAmounts) {
		CallableStatement dmlStmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			OracleConnection oracleConn = (OracleConnection) wrappedConn.getUnderlyingConnection();

			dmlStmt = wrappedConn.prepareCall(
					"{call ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatches(?,?,?,?,?,?,?)}");
			dmlStmt.setString(1, fulfillmentMethod);
			setNullableString(dmlStmt, 2, paymentTypeGroup);
			dmlStmt.setDate(3, inDueDate);
			dmlStmt.setString(4, inUser);
			dmlStmt.registerOutParameter(5, OracleTypes.ARRAY, ORACLE_NUMBER10_ARRAY_TYPENAME);

			if (inBusinessLineIds != null && inBusinessLineIds.length > 0) {
				Array blArray = toNumber10Array(oracleConn, inBusinessLineIds);
				dmlStmt.setObject(6, blArray, OracleTypes.ARRAY);
				Array cutoffArray = toNullableNumber10Array(oracleConn, inBatchCutoffAmounts);
				dmlStmt.setObject(7, cutoffArray, OracleTypes.ARRAY);
			} else {
				dmlStmt.setNull(6, OracleTypes.ARRAY, ORACLE_NUMBER10_ARRAY_TYPENAME);
				dmlStmt.setNull(7, OracleTypes.ARRAY, ORACLE_NUMBER10_ARRAY_TYPENAME);
			}

			logger.info("Calling generateFulfillmentBatches: fulfillmentMethod=" + fulfillmentMethod
					+ ", paymentTypeGroup=" + paymentTypeGroup + ", dueDate=" + inDueDate + ", user=" + inUser
					+ ", businessLineCount=" + (inBusinessLineIds == null ? 0 : inBusinessLineIds.length));
			dmlStmt.execute();
			logger.info("generateFulfillmentBatches: Created batchIds="
					+ Arrays.toString((Object[]) dmlStmt.getArray(5).getArray()));
			closeQuietly(dmlStmt);
			dmlStmt = null;
			return searchEarnedOnConnection(wrappedConn, fulfillmentMethod, paymentTypeGroup, inDueDate);
		} catch (Exception e) {
			logger.error("generateFulfillmentBatches: " + e.getMessage(), e);
			return new HonorariaSearchResult();
		} finally {
			closeQuietly(dmlStmt);
			closeQuietly(wrappedConn);
		}
	}

	/**
	 * {@code ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatch} for a single business line, then
	 * {@code ACCOUNTING.HonorariaRepositoryCur.searchEarned} on the same connection (same transaction).
	 *     ACCOUNTING.HONORARIAREPOSITORYDML.GENERATEFULFILLMENTBATCH(fulfillmentMethod,paymentTypeGroup,dueDate,userName,outTotalFulfillments,outTotalApprovedAmount,outBatchID,businessLineIDPV);

	 */
	public HonorariaSearchResult generateFulfillmentBatch(String fulfillmentMethod, String paymentTypeGroup, java.sql.Date inDueDate, String inUser, long inBusinessLineId) {
		CallableStatement dmlStmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		
		logger.info("Calling generateFulfillmentBatch: dueDate=" + inDueDate + ",inPaymentTypeGroup=" + paymentTypeGroup + ", fulfillmentMethod=" + fulfillmentMethod
				+ ", businessLineId=" + inBusinessLineId + ", user=" + inUser);
		
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			dmlStmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaRepositoryDML.generateFulfillmentBatch(?,?,?,?,?,?,?,?)}");
			dmlStmt.setString(1, fulfillmentMethod);
			dmlStmt.setString(2, paymentTypeGroup);
			dmlStmt.setDate(3, inDueDate);
			dmlStmt.setString(4, inUser);
			dmlStmt.registerOutParameter(5, OracleTypes.NUMBER);
			dmlStmt.registerOutParameter(6, OracleTypes.NUMBER);
			dmlStmt.registerOutParameter(7, OracleTypes.NUMBER);
			dmlStmt.setLong(8, inBusinessLineId);
			
			dmlStmt.execute();
			logger.info("generateFulfillmentBatch: Created batchId=" + dmlStmt.getLong(6) + ", totalFulfillments=" + dmlStmt.getInt(5) + ", totalApprovedAmount=" + dmlStmt.getInt(6));
			closeQuietly(dmlStmt);
			dmlStmt = null;
			return searchEarnedOnConnection(wrappedConn, fulfillmentMethod, paymentTypeGroup, inDueDate);
		} catch (Exception e) {
			logger.error("generateFulfillmentBatch: " + e.getMessage(), e);
			return new HonorariaSearchResult();
		} finally {
			closeQuietly(dmlStmt);
			closeQuietly(wrappedConn);
		}
	}
	
	
	/**
	 * {@code ACCOUNTING.HonorariaRepositoryCur.searchEarned} on an open connection so it participates in the same
	 * transaction as other work on that connection (e.g. {@code generateFulfillmentBatches}).
	 */
	private HonorariaSearchResult searchEarnedOnConnection(WrappedConnection wrappedConn, String fulfillmentMethod,	String paymentTypeGroup, java.sql.Date inDueDate) {
		CallableStatement stmt = null;
		ResultSet summaryRs = null;
		ResultSet exceptRs = null;
		HonorariaSearchResult result = new HonorariaSearchResult();
		try {
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaRepositoryCur.searchEarned(?,?,?,?,?,?)}");
			stmt.setString(1, fulfillmentMethod);
			stmt.setString(2, paymentTypeGroup);
			stmt.setDate(3, inDueDate);
			stmt.registerOutParameter(4, OracleTypes.CURSOR);
			stmt.registerOutParameter(5, OracleTypes.NUMBER);
			stmt.registerOutParameter(6, OracleTypes.CURSOR);
			logger.info("searchEarnedOnConnection: fulfillmentMethod=" + fulfillmentMethod + ", inPaymentTypeGroup=" + paymentTypeGroup + ", dueDate=" + inDueDate);
			stmt.execute();
			summaryRs = (ResultSet) stmt.getObject(4);
			readSummaryCursor(summaryRs, result);
			result.setExceptionCount(stmt.getInt(5));
			if (result.getExceptionCount() != 0) {
				exceptRs = (ResultSet) stmt.getObject(6);
				readExceptionCursor(exceptRs, result);
			}
		} catch (Exception e) {
			logger.error("searchEarnedOnConnection: " + e.getMessage(), e);
		} finally {
			closeQuietly(summaryRs);
			closeQuietly(exceptRs);
			closeQuietly(stmt);
		}
		return result;
	}

	
	public HonorariaSearchResult searchEarned(String fulfillmentMethod, String paymentTypeGroup, java.sql.Date inDueDate) {
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		ResultSet summaryRs = null;
		ResultSet exceptRs = null;
		HonorariaSearchResult result = new HonorariaSearchResult();
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			return searchEarnedOnConnection(wrappedConn, fulfillmentMethod, paymentTypeGroup, inDueDate);
		} catch (Exception e) {
			logger.error("searchEarned: " + e.getMessage(), e);
			return new HonorariaSearchResult();
		} finally {
			closeQuietly(summaryRs);
			closeQuietly(exceptRs);
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
	}

	
	/**
	 * {@code ACCOUNTING.HonorariaBatchUtil.runLoopback}
	 * loopback - Amazon they currently close it quickly by just pasting the order# from portal when they submit the file.  
	 * It doesn't do anything to tie to individual giftcards.  
	 * 
	 * For ADP they go to ADP portal and download a file/report that is CSV with all the check numbers for our checks in file we submitted.  
	 * They place that out in nfs mount, push button in tool to execute db code to load that check# file so we can tie all 
	 * fulfillments to their check# for support/tax/escheatment/etc.
	 */
	public boolean runLoopback(long inBatchId, String inUser, String inOrderNumber) {
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		boolean retVal = true;
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaBatchUtil.runLoopback(?,?,?)}");
			stmt.setLong(1, inBatchId);
			stmt.setString(2, inUser);
			stmt.setString(3, inOrderNumber);
			logger.info("runLoopback: batchId=" + inBatchId);
			stmt.execute();
		} catch (Exception e) {
			logger.error("runLoopback: " + e.getMessage(), e);
			retVal = false;
		} finally {
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
		return retVal;
	}

	
	/**
	 * {@code ACCOUNTING.HonorariaBatchUtil.generateFiles}
	 * generate files - generates files to file system (mounted nfs share finance can access) in applicable format for ADP and Amazon.  
	 * If ADP, a script scott wrote will be executed to SFTP it over to ADP.  
	 * If Amazon, the user goes to the Amazon portal and uploads the file from the nfs share/mount.
	 * inProposedFulfillmentDate taken out as input parameter since it defaults to TRUNC(SYSDATE)+7 
	 */
	public boolean generateFiles(String[] inBatchIds, String inUser, java.sql.Date inProposedFulfillmentDate) {
		if (inBatchIds == null || inBatchIds.length == 0) {
			logger.warn("generateFiles: no batch ids supplied; skipping call");
			return false;
		}
		boolean retVal = true;
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			OracleConnection oracleConn = (OracleConnection) wrappedConn.getUnderlyingConnection();
			BigDecimal[] batchIdNums = new BigDecimal[inBatchIds.length];
			for (int i = 0; i < inBatchIds.length; i++) {
				batchIdNums[i] = new BigDecimal(inBatchIds[i]);
			}
			Array batchArray = oracleConn.createOracleArray(ORACLE_NUMBER10_ARRAY_TYPENAME, batchIdNums);
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaBatchUtil.generateFiles(?,?)}");
			stmt.setObject(1, batchArray, OracleTypes.ARRAY);
			setNullableString(stmt, 2, inUser);
			logger.info("generateFiles: batchCount=" + inBatchIds.length);
			stmt.execute();
		} catch (Exception e) {
			logger.error("generateFiles: " + e.getMessage(), e);
			retVal = false;
		} finally {
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
		return retVal;
	}

	
	/**
	 * {@code ACCOUNTING.HonorariaBatchUtil.generateFile}
	 * generate file - generates file to file system (mounted nfs share finance can access) in applicable format for ADP and Amazon.  
	 * If ADP, a script scott wrote will be executed to SFTP it over to ADP.  
	 * If Amazon, the user goes to the Amazon portal and uploads the file from the nfs share/mount.
	 * inProposedFulfillmentDate taken out as input parameter since it defaults to TRUNC(SYSDATE)+7 
	 */
	public boolean generateFile(long inBatchId, String inUser, java.sql.Date inProposedFulfillmentDate) {
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		boolean retVal = true;
		
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			stmt = wrappedConn.prepareCall("{call ACCOUNTING.HonorariaBatchUtil.generateFile(?,?)}");
			stmt.setLong(1, inBatchId);
			setNullableString(stmt, 2, inUser);
			logger.info("generateFile: batchId=" + inBatchId);
			stmt.execute();
		} catch (Exception e) {
			logger.error("generateFile: " + e.getMessage(), e);
			retVal = false;
		} finally {
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
		return retVal;
	}

	/**
	 * {@code ACCOUNTING.FulfillmentBatchesCur.openIncompleteCur(outHasRows OUT NUMBER) RETURN incompleteCur}
	 * <p>
	 * If the function is declared in a different package, update the JDBC call string accordingly.
	 */
	public OpenIncompleteCurResult checkForIncompleteBatch() {
		CallableStatement stmt = null;
		DataSource ds = getDataSourceFromHibernateEntityManager();
		WrappedConnection wrappedConn = null;
		ResultSet rs = null;
		OpenIncompleteCurResult result = new OpenIncompleteCurResult();
		try {
			wrappedConn = (WrappedConnection) ds.getConnection();
			stmt = wrappedConn.prepareCall("{? = call ACCOUNTING.FulfillmentBatchesCur.openIncompleteCur(?)}");
			stmt.registerOutParameter(1, OracleTypes.CURSOR);
			stmt.registerOutParameter(2, OracleTypes.NUMBER);
			logger.info("openIncompleteCur: calling ACCOUNTING.FulfillmentBatchesCur.openIncompleteCur");
			stmt.execute();
			rs = (ResultSet) stmt.getObject(1);
			result.setOutHasRows(stmt.getInt(2));
			readIncompleteFulfillmentCursor(rs, result);
		} catch (Exception e) {
			logger.error("openIncompleteCur: " + e.getMessage(), e);
		} finally {
			closeQuietly(rs);
			closeQuietly(stmt);
			closeQuietly(wrappedConn);
		}
		return result;
	}

	private static void readIncompleteFulfillmentCursor(ResultSet rs, OpenIncompleteCurResult result) throws SQLException {
		if (rs == null) {
			return;
		}
		List<IncompleteFulfillmentBatchRow> rows = new ArrayList<>();
		while (rs.next()) {
			IncompleteFulfillmentBatchRow row = new IncompleteFulfillmentBatchRow();
			row.setBatchId(rs.getLong(1));
			row.setBusinessLineId(rs.getLong(2));
			row.setBusinessLineDesc(rs.getString(3));
			row.setBusinessLineFinanceCode(rs.getString(4));
			row.setFulfillmentMethod(rs.getString(5));
			row.setFulfillmentCount(rs.getInt(6));
			BigDecimal amt = rs.getBigDecimal(7);
			row.setSumFulfillmentAmount(rs.wasNull() ? null : amt);
			row.setStatusCode(rs.getString(8));
			rows.add(row);
		}
		result.setRows(rows);
	}

	private static void readSummaryCursor(ResultSet rs, HonorariaSearchResult result) throws SQLException {
		if (rs == null) {
			return;
		}
		List<HonorariaSearchSummaryRow> rows = new ArrayList<>();
		while (rs.next()) {
			HonorariaSearchSummaryRow row = new HonorariaSearchSummaryRow();
			row.setBatchId(rs.getLong(1));
			row.setBusinessLineId(rs.getLong(2));
			row.setBusinessLineDesc(rs.getString(3));
			
			row.setBusinessLineFinanceCode(rs.getString(4));
			
			
			row.setTotalFulfillments(rs.getInt(5));
			BigDecimal amt = rs.getBigDecimal(6);
			row.setTotalApprovedAmount(rs.wasNull() ? null : amt);
			rows.add(row);
		}
		result.setSummaryRows(rows);
	}

	private static void readExceptionCursor(ResultSet rs, HonorariaSearchResult result) throws SQLException {
		if (rs == null) {
			return;
		}
		List<HonorariaSearchExceptionRow> rows = new ArrayList<>();
		while (rs.next()) {
			HonorariaSearchExceptionRow ex = new HonorariaSearchExceptionRow();
			ex.setCode(rs.getInt(1));
			ex.setMessage(rs.getString(2));
			ex.setDetail(rs.getString(3));
			rows.add(ex);
		}
		result.setExceptions(rows);
	}

	private static void setNullableString(CallableStatement stmt, int index, String value) throws SQLException {
		if (value != null) {
			stmt.setString(index, value);
		} else {
			stmt.setNull(index, OracleTypes.VARCHAR);
		}
	}

	private static Array toNumber10Array(OracleConnection oracleConn, long[] values) throws SQLException {
		BigDecimal[] boxed = new BigDecimal[values.length];
		for (int i = 0; i < values.length; i++) {
			boxed[i] = BigDecimal.valueOf(values[i]);
		}
		return oracleConn.createOracleArray(ORACLE_NUMBER10_ARRAY_TYPENAME, boxed);
	}

	private static Array toNullableNumber10Array(OracleConnection oracleConn, Long[] values) throws SQLException {
		if (values == null) {
			return null;
		}
		BigDecimal[] boxed = new BigDecimal[values.length];
		for (int i = 0; i < values.length; i++) {
			boxed[i] = values[i] == null ? null : BigDecimal.valueOf(values[i].longValue());
		}
		return oracleConn.createOracleArray(ORACLE_NUMBER10_ARRAY_TYPENAME, boxed);
	}

	private static void closeQuietly(ResultSet rs) {
		if (rs != null) {
			try {
				rs.close();
			} catch (SQLException ignore) {
			}
		}
	}

	private static void closeQuietly(CallableStatement stmt) {
		if (stmt != null) {
			try {
				stmt.close();
			} catch (SQLException ignore) {
			}
		}
	}

	private static void closeQuietly(WrappedConnection wrappedConn) {
		if (wrappedConn != null) {
			try {
				wrappedConn.close();
			} catch (SQLException ignore) {
			}
		}
	}

	public List<ActivityHonoraria> getActivityHonorariaByProgram(Long programId) {
		String sql = "SELECT ah FROM ActivityHonoraria ah, Event e "
				+ "WHERE ah.programEventId = :programEventId "
				+ "AND ah.eventId = e.eventId "
				+ "AND e.eventSpecifier='QUALHOLD' "
				+ "AND ah.endLogRecordId IS NULL "
				+ "ORDER BY ah.logRecordId";	
		
		Query query = entityManager.createQuery(sql);
		query.setParameter("programEventId", programId);

		List<ActivityHonoraria> results = (List<ActivityHonoraria>)query.getResultList();
		
		if (results == null || results.isEmpty()) {
			logger.info("getActivityHonorariaByProgram: No activity honoraria found for programEventId: " + programId);
		} else {
			logger.info("getActivityHonorariaByProgram: Found " + results.size() + " activity honoraria for programEventId: " + programId);
		}
		
		return results;
	}

	
}