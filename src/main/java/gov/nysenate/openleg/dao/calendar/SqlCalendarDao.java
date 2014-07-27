package gov.nysenate.openleg.dao.calendar;

import com.google.common.collect.*;
import gov.nysenate.openleg.dao.base.LimitOffset;
import gov.nysenate.openleg.dao.base.OrderBy;
import gov.nysenate.openleg.dao.base.SortOrder;
import gov.nysenate.openleg.dao.base.SqlBaseDao;
import gov.nysenate.openleg.model.bill.BillId;
import gov.nysenate.openleg.model.calendar.*;
import gov.nysenate.openleg.model.calendar.Calendar;
import gov.nysenate.openleg.model.sobi.SobiFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static gov.nysenate.openleg.dao.calendar.SqlCalendarQuery.*;

@Repository
public class SqlCalendarDao extends SqlBaseDao implements CalendarDao
{
    private static final Logger logger = LoggerFactory.getLogger(SqlCalendarDao.class);

    /** {@inheritDoc} */
    @Override
    public Calendar getCalendar(CalendarId calendarId) throws DataAccessException {
        MapSqlParameterSource calParams = new MapSqlParameterSource();
        addCalendarIdParams(calendarId, calParams);
        // Get the base calendar
        Calendar calendar = jdbcNamed.queryForObject(SELECT_CALENDAR.getSql(schema()), calParams, new CalendarRowMapper());
        // Get the supplementals
        calendar.setSupplementalMap(getCalSupplementals(calParams));
        // Get the active lists
        calendar.setActiveListMap(getActiveListMap(calParams));
        return calendar;
    }

    /** {@inheritDoc} */
    @Override
    public List<CalendarId> getCalendarIds(int year, SortOrder calOrder) {
        OrderBy orderBy = new OrderBy("calendarNo", calOrder);
        MapSqlParameterSource params = new MapSqlParameterSource("year", year);
        List<Calendar> calendars =
            jdbcNamed.query(SELECT_CALENDAR.getSql(schema(), orderBy, LimitOffset.ALL), params, new CalendarRowMapper());
        List<CalendarId> calendarIds = new ArrayList<>();
        for (Calendar calendar : calendars) {
            calendarIds.add(calendar.getId());
        }
        return calendarIds;
    }

    /** {@inheritDoc} */
    @Override
    public void updateCalendar(Calendar calendar, SobiFragment fragment) throws DataAccessException {
        logger.trace("Updating calendar {} in database...", calendar);
        MapSqlParameterSource calParams = getCalendarParams(calendar, fragment);
        // Update base calendar
        if (jdbcNamed.update(UPDATE_CALENDAR.getSql(schema()), calParams) == 0) {
            jdbcNamed.update(INSERT_CALENDAR.getSql(schema()), calParams);
        }
        // Update the associated calendar supplementals
        updateCalSupplementals(calendar, fragment, calParams);
        // Update the associated active lists
        updateCalActiveLists(calendar, fragment, calParams);
    }

    /** --- Internal Methods --- */

    /**
     * Retrieves all the supplementals for a particular calendar.
     */
    private TreeMap<String, CalendarSupplemental> getCalSupplementals(MapSqlParameterSource calParams) {
        List<CalendarSupplemental> calSupList =
                jdbcNamed.query(SELECT_CALENDAR_SUPS.getSql(schema()), calParams, new CalendarSupRowMapper());
        TreeMap<String, CalendarSupplemental> supMap = new TreeMap<>();
        for (CalendarSupplemental calSup : calSupList) {
            calParams.addValue("supVersion", calSup.getVersion());
            // Add the supplemental entries
            calSup.setSectionEntries(getCalSupEntries(calParams));
            supMap.put(calSup.getVersion(), calSup);
        }
        return supMap;
    }

    /**
     * Retrieves the supplemental entries for a particular supplemental.
     */
    private LinkedListMultimap<CalendarSectionType, CalendarSupplementalEntry> getCalSupEntries(
            MapSqlParameterSource supParams) {
        List<CalendarSupplementalEntry> entries =
            jdbcNamed.query(SELECT_CALENDAR_SUP_ENTRIES.getSql(schema()), supParams, new CalendarSupEntryRowMapper());
        LinkedListMultimap<CalendarSectionType, CalendarSupplementalEntry> sectionEntries = LinkedListMultimap.create();
        for (CalendarSupplementalEntry entry : entries) {
            sectionEntries.put(entry.getSectionType(), entry);
        }
        return sectionEntries;
    }

    /**
     * Updates the calendar supplementals. Entries belonging to supplementals that have not changed will not
     * be affected.
     */
    private void updateCalSupplementals(Calendar calendar, SobiFragment fragment, MapSqlParameterSource calParams) {
        Map<String, CalendarSupplemental> existingCalSupMap = getCalSupplementals(calParams);
        // Get the difference between the existing and current supplemental mappings
        MapDifference<String, CalendarSupplemental> diff =
            Maps.difference(existingCalSupMap, calendar.getSupplementalMap());
        // Delete any supplementals that were not found in the current map or were different.
        Set<String> deleteSupVersions = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnLeft().keySet());
        for (String supVersion : deleteSupVersions) {
            calParams.addValue("supVersion", supVersion);
            jdbcNamed.update(DELETE_CALENDAR_SUP.getSql(schema()), calParams);
        }
        // Insert any new or differing supplementals
        Set<String> updateSupVersions = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnRight().keySet());
        for (String supVersion : updateSupVersions) {
            CalendarSupplemental sup = calendar.getSupplemental(supVersion);
            MapSqlParameterSource supParams = getCalSupplementalParams(sup, fragment);
            jdbcNamed.update(INSERT_CALENDAR_SUP.getSql(schema()), supParams);
            // Insert the calendar entries
            for (CalendarSupplementalEntry entry : sup.getSectionEntries().values()) {
                MapSqlParameterSource entryParams = getCalSupEntryParams(sup, entry);
                jdbcNamed.update(INSERT_CALENDAR_SUP_ENTRY.getSql(schema()), entryParams);
            }
        }
    }

    /**
     * Retrieve the active list mappings for a specific calendar.
     */
    private TreeMap<Integer, CalendarActiveList> getActiveListMap(MapSqlParameterSource calParams) {
        List<CalendarActiveList> activeLists =
            jdbcNamed.query(SELECT_CALENDAR_ACTIVE_LISTS.getSql(schema()), calParams, new CalendarActiveListRowMapper());
        TreeMap<Integer, CalendarActiveList> activeListMap = new TreeMap<>();
        for (CalendarActiveList activeList : activeLists) {
            calParams.addValue("activeListNo", activeList.getId());
            activeList.setEntries(getActiveListEntries(calParams));
            activeListMap.put(activeList.getId(), activeList);
        }
        return activeListMap;
    }

    /**
     * Retrieve entries for a specific active list.
     */
    private List<CalendarActiveListEntry> getActiveListEntries(MapSqlParameterSource activeListParams) {
        return jdbcNamed.query(SELECT_CALENDAR_ACTIVE_LIST_ENTRIES.getSql(schema()), activeListParams,
                               new CalendarActiveListEntryRowMapper());
    }

    /**
     * Updates the calendar active lists. Entries belonging to active lists that have not changed will not
     * be affected.
     */
    private void updateCalActiveLists(Calendar calendar, SobiFragment fragment, MapSqlParameterSource calParams) {
        Map<Integer, CalendarActiveList> existingActiveListMap = getActiveListMap(calParams);
        // Get the difference between the existing and current active list mappings.
        MapDifference<Integer, CalendarActiveList> diff =
                Maps.difference(existingActiveListMap, calendar.getActiveListMap());
        // Delete any active lists that were not found in the current map or were different.
        Set<Integer> deleteActListIds = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnLeft().keySet());
        for (Integer actListId : deleteActListIds) {
            calParams.addValue("activeListNo", actListId);
            jdbcNamed.update(DELETE_CALENDAR_ACTIVE_LIST.getSql(schema()), calParams);
        }
        // Insert any new or differing active lists
        Set<Integer> updateActListIds = Sets.union(diff.entriesDiffering().keySet(), diff.entriesOnlyOnRight().keySet());
        for (Integer actListId : updateActListIds) {
            CalendarActiveList actList = calendar.getActiveList(actListId);
            MapSqlParameterSource actListParams = getCalActiveListParams(actList, fragment);
            jdbcNamed.update(INSERT_CALENDAR_ACTIVE_LIST.getSql(schema()), actListParams);
            // Insert the active list entries
            for (CalendarActiveListEntry entry : actList.getEntries()) {
                MapSqlParameterSource entryParams = getCalActiveListEntryParams(actList, entry);
                jdbcNamed.update(INSERT_CALENDAR_ACTIVE_LIST_ENTRY.getSql(schema()), entryParams);
            }
        }
    }

    /** --- Helper Classes --- */

    protected class CalendarRowMapper implements RowMapper<Calendar>
    {
        @Override
        public Calendar mapRow(ResultSet rs, int rowNum) throws SQLException {
            Calendar calendar = new Calendar(new CalendarId(rs.getInt("calendar_no"), rs.getInt("year")));
            calendar.setModifiedDate(rs.getTimestamp("modified_date_time"));
            calendar.setPublishDate(rs.getTimestamp("published_date_time"));
            return calendar;
        }
    }

    protected class CalendarSupRowMapper implements RowMapper<CalendarSupplemental>
    {
        @Override
        public CalendarSupplemental mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarId calendarId = new CalendarId(rs.getInt("calendar_no"), rs.getInt("calendar_year"));
            String version = rs.getString("sup_version");
            Date calDate = rs.getDate("calendar_date");
            Date releaseDateTime = rs.getTimestamp("release_date_time");
            CalendarSupplemental calSup = new CalendarSupplemental(calendarId, version, calDate, releaseDateTime);
            calSup.setModifiedDate(rs.getTimestamp("modified_date_time"));
            calSup.setPublishDate(rs.getTimestamp("published_date_time"));
            return calSup;
        }
    }

    protected class CalendarSupEntryRowMapper implements RowMapper<CalendarSupplementalEntry>
    {
        @Override
        public CalendarSupplementalEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarSectionType sectionType = CalendarSectionType.valueOfCode(rs.getInt("section_code"));
            int billCalNo = rs.getInt("bill_calendar_no");
            BillId billId = new BillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"),
                                       rs.getString("bill_amend_version"));
            BillId subBillId = null;
            if (rs.getString("sub_bill_print_no") != null) {
                subBillId = new BillId(rs.getString("sub_bill_print_no"), rs.getInt("sub_bill_session_year"),
                                       rs.getString("sub_bill_amend_version"));
            }
            boolean high = rs.getBoolean("high");
            return new CalendarSupplementalEntry(billCalNo, sectionType, billId, subBillId, high);
        }
    }

    protected class CalendarActiveListRowMapper implements RowMapper<CalendarActiveList>
    {
        @Override
        public CalendarActiveList mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarActiveList activeList = new CalendarActiveList();
            activeList.setId(rs.getInt("active_list_no"));
            activeList.setCalendarId(new CalendarId(rs.getInt("calendar_no"), rs.getInt("calendar_year")));
            activeList.setCalDate(rs.getDate("calendar_date"));
            activeList.setReleaseDateTime(rs.getTimestamp("release_date_time"));
            activeList.setModifiedDate(rs.getTimestamp("modified_date_time"));
            activeList.setPublishDate(rs.getTimestamp("published_date_time"));
            return activeList;
        }
    }

    protected class CalendarActiveListEntryRowMapper implements RowMapper<CalendarActiveListEntry>
    {
        @Override
        public CalendarActiveListEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
            CalendarActiveListEntry entry = new CalendarActiveListEntry();
            entry.setBillCalNo(rs.getInt("bill_calendar_no"));
            entry.setBillId(new BillId(rs.getString("bill_print_no"), rs.getInt("bill_session_year"),
                                       rs.getString("bill_amend_version")));
            return entry;
        }
    }

    /** --- Param Source Methods --- */

    protected static MapSqlParameterSource getCalendarParams(Calendar calendar, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(calendar.getId(), params);
        addModPubDateParams(calendar.getModifiedDate(), calendar.getPublishDate(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalSupplementalParams(CalendarSupplemental sup, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(sup.getCalendarId(), params);
        params.addValue("supVersion", sup.getVersion());
        params.addValue("calendarDate", sup.getCalDate());
        params.addValue("releaseDateTime", sup.getReleaseDateTime());
        addModPubDateParams(sup.getModifiedDate(), sup.getPublishDate(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalSupEntryParams(CalendarSupplemental sup, CalendarSupplementalEntry entry) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(sup.getCalendarId(), params);
        params.addValue("supVersion", sup.getVersion());
        params.addValue("sectionCode", entry.getSectionType().getCode());
        params.addValue("billCalNo", entry.getBillCalNo());
        addBillIdParams(entry.getBillId(), params);
        BillId subBillId = entry.getSubBillId();
        params.addValue("subPrintNo", (subBillId != null) ? subBillId.getPrintNo() : null);
        params.addValue("subSession", (subBillId != null) ? subBillId.getSession() : null);
        params.addValue("subAmendVersion", (subBillId != null) ? subBillId.getVersion() : null);
        params.addValue("high", entry.getBillHigh());
        return params;
    }

    protected static MapSqlParameterSource getCalActiveListParams(CalendarActiveList actList, SobiFragment fragment) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(actList.getCalendarId(), params);
        params.addValue("activeListNo", actList.getId());
        params.addValue("calendarDate", actList.getCalDate());
        params.addValue("releaseDateTime", actList.getReleaseDateTime());
        params.addValue("notes", actList.getNotes());
        addModPubDateParams(actList.getModifiedDate(), actList.getPublishDate(), params);
        addLastFragmentParam(fragment, params);
        return params;
    }

    protected static MapSqlParameterSource getCalActiveListEntryParams(CalendarActiveList actList,
                                                                       CalendarActiveListEntry entry) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCalendarIdParams(actList.getCalendarId(), params);
        params.addValue("activeListNo", actList.getId());
        params.addValue("billCalendarNo", entry.getBillCalNo());
        addBillIdParams(entry.getBillId(), params);
        return params;
    }

    protected static void addCalendarIdParams(CalendarId calendarId, MapSqlParameterSource params) {
        params.addValue("calendarNo", calendarId.getCalNo());
        params.addValue("year", calendarId.getYear());
    }

    protected static void addBillIdParams(BillId billId, MapSqlParameterSource params) {
        params.addValue("printNo", billId.getPrintNo());
        params.addValue("session", billId.getSession());
        params.addValue("amendVersion", billId.getVersion());
    }
}
