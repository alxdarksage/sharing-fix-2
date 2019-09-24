package org.sagebionetworks.bridge.scripts.sharing_fix_2;

import static java.lang.Boolean.TRUE;
import static org.joda.time.DateTimeZone.UTC;
import static org.sagebionetworks.bridge.rest.model.AccountStatus.ENABLED;
import static org.sagebionetworks.bridge.rest.model.SharingScope.ALL_QUALIFIED_RESEARCHERS;
import static org.sagebionetworks.bridge.rest.model.SharingScope.NO_SHARING;
import static org.sagebionetworks.bridge.rest.model.SharingScope.SPONSORS_AND_PARTNERS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.rest.model.SharingScope;
import org.sagebionetworks.bridge.rest.model.SignIn;
import org.sagebionetworks.bridge.rest.model.StudyParticipant;
import org.sagebionetworks.bridge.rest.model.Upload;
import org.sagebionetworks.bridge.rest.model.UploadList;

public class SharingScopeFixer {
    private static final Logger LOG = LoggerFactory.getLogger(SharingScopeFixer.class);
    
    /** 
     * 2019-08-29T14:45:05-07:00 is the timestamp of the broken deployment.
     * 
     * TODO: What about clock skew on the prod machines? Should we shift this back by 
     * 10 minutes or an hour? If we find uploads with sharing set, we'll just leave 
     * those alone, so this should be pretty safe, unless we miss that one upload
     * right before the deployment that would have told us the prior sharing state.
     */
    public final DateTime EVENT_START = new DateTime(1567115105000L);
    
    AppHelper helper;
    List<UserInfo> users;
    SignIn signIn;

    public SharingScopeFixer(List<UserInfo> users, SignIn signIn, AppHelper helper) {
        this.users = users;
        this.signIn = signIn;
        this.helper = helper;
    }
    
    public void run() throws Exception {
        helper.adminSignIn(signIn.getStudy());
        
        String currentStudyId = "api";
        for (UserInfo userInfo : users) {
            currentStudyId = changeStudyIfNecessary(userInfo, currentStudyId);
            
            StudyParticipant participant = helper.getParticipantById(userInfo.getId());
            // If the user isn't enabled or isn't consented, don't turn on their sharing status
            if (participant.getStatus() != ENABLED || !TRUE.equals(participant.isConsented())) {
                continue;
            }
            // The sharing scope we'll use if we can't determine a more accurate value from historical 
            // records. Samsung was known to set the widest sharing with no choice. 
            SharingScope scope = ("samsung-blood-pressure".equals(userInfo.getStudyId())) ?
                    ALL_QUALIFIED_RESEARCHERS :
                    SPONSORS_AND_PARTNERS;
            
            // If user was created *after* bug was introduced, there's no point in looking in history 
            // for a better choice of sharing scope
            if (!participant.getCreatedOn().isAfter(EVENT_START)) {
                // Query health data records in reverse chronological order until you find the last 
                // sharing scope that was submitted by the user, if any
                SharingScope foundScope = findPriorSharingScope(userInfo.getId(), participant.getCreatedOn());
                if (foundScope != null) {
                    scope = foundScope;
                }
            }
            // Don't update if the user intended to turn off sharing prior to the defect introduction
            if (scope != NO_SHARING) {
                participant.setSharingScope(scope);
                helper.updateParticipant(participant);
                LOG.info("UPDATED USER: " + userInfo.getId() + " to sharing " + scope);
                
                List<String> recordIds = getHealthRecordsToChange(userInfo.getId());
                for (String recordId : recordIds) {
                    helper.changeHealthRecordSharingScope(recordId, scope);
                    // TODO: Is this what we need to redrive the export? Log whatever we will need for that.
                    // Can we log the JSON we will need to submit to the exporter? Let's do that and save
                    // some trouble
                    LOG.info("UPDATED HEALTH RECORD: " + recordId);
                }
            }
            Thread.sleep(1000);
        }
    }
    
    String changeStudyIfNecessary(UserInfo userInfo, String currentStudyId) throws IOException {
        if (currentStudyId.equals(userInfo.getStudyId())) {
            return currentStudyId;
        }
        helper.adminChangeStudy(userInfo.getStudyId());
        return userInfo.getStudyId();
    }
    
    List<String> getHealthRecordsToChange(String userId) throws Exception {
        // TODO: clock skew, should we check before running?
        DateTime start = new DateTime(EVENT_START); 
        DateTime end = DateTime.now().plusHours(1); // don't want clock skew to miss any
        List<String> healthRecordIds = new ArrayList<>();
        String offsetKey = null;
        do {
            UploadList list = helper.getParticipantUploads(userId, start, end, offsetKey);
            for (Upload upload : list.getItems()) {
                if (upload.getRecordId() != null) {
                    healthRecordIds.add(upload.getRecordId());
                }
            }
            offsetKey = list.getNextPageOffsetKey();
        } while(offsetKey != null);
        return healthRecordIds;
    }
    
    SharingScope findPriorSharingScope(String userId, DateTime createdOn) throws IOException {
        // This uses the most recent record with a sharing scope by reversing the order that uploads 
        // are returned by the API for a time period, and working backwards time period by time period
        // until a value is found. We'll work all the way back to the createdOn value of the user's
        // account, because an upload may not have occurred for a long time.
        int daysStart = 0;
        int daysEnd = 0;
        while(EVENT_START.minusDays(daysStart).isAfter(createdOn)) {
            daysEnd = daysStart;
            daysStart += 45;
            // should this be days end? Work it out on paper.
            if (EVENT_START.minusDays(daysStart).isBefore(createdOn)) {
                return null;
            }
            String offsetKey = null;
            List<Upload> allUploadsInDateRange = new ArrayList<>();
            do {
                DateTime startTime = EVENT_START.minusDays(daysStart).withZone(UTC);
                DateTime endTime = EVENT_START.minusDays(daysEnd).withZone(UTC);
                
                UploadList list = helper.getParticipantUploads(userId, startTime, endTime, offsetKey);
                for (Upload upload : list.getItems()) {
                    // when completedOn != null, the full record will have a healthData record
                    // so only keep these records... without this value the upload never finished
                    if (upload.getCompletedOn() != null) {
                        allUploadsInDateRange.add(upload);
                    }
                }
                offsetKey = list.getNextPageOffsetKey();
            } while(offsetKey != null);
            
            for (int i= allUploadsInDateRange.size()-1; i >= 0; i--) {
                Upload upload = allUploadsInDateRange.get(i);
                if (upload.getRecordId() != null) {
                    Upload fullUpload = helper.getUploadByRecordId(upload.getRecordId());
                    if (fullUpload.getHealthData() != null &&
                        fullUpload.getHealthData().getUserSharingScope() != null) { 
                        return fullUpload.getHealthData().getUserSharingScope();
                    }
                }
            }
            // This page didn't find a completed upload with a sharing setting,
            // shift back in time by 45 days and try again. 
        }
        return null;
    }    
}
