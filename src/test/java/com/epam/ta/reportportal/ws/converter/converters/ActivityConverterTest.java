package com.epam.ta.reportportal.ws.converter.converters;

import com.epam.ta.reportportal.entity.activity.Activity;
import com.epam.ta.reportportal.entity.activity.ActivityDetails;
import com.epam.ta.reportportal.entity.activity.HistoryField;
import com.epam.ta.reportportal.ws.model.ActivityResource;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
public class ActivityConverterTest {

	@Test(expected = NullPointerException.class)
	public void testNull() {
		ActivityConverter.TO_RESOURCE.apply(null);
	}

	@Test
	public void testConvert() {
		Activity activity = new Activity();
		activity.setId(1L);
		activity.setAction("LAUNCH_STARTED");
		activity.setCreatedAt(LocalDateTime.now());
		final ActivityDetails details = new ActivityDetails();
		details.setObjectName("objectName");
		details.setHistory(Collections.singletonList(HistoryField.of("filed", "old", "new")));
		activity.setDetails(details);
		activity.setUsername("username");
		activity.setActivityEntityType(Activity.ActivityEntityType.LAUNCH);
		activity.setProjectId(2L);
		activity.setUserId(3L);
		validate(activity, ActivityConverter.TO_RESOURCE.apply(activity));
	}

	private void validate(Activity db, ActivityResource resource) {
		assertEquals(Date.from(db.getCreatedAt().atZone(ZoneId.of("UTC")).toInstant()), resource.getLastModifiedDate());
		assertEquals(String.valueOf(db.getId()), resource.getActivityId());
		assertEquals(db.getActivityEntityType(), Activity.ActivityEntityType.fromString(resource.getObjectType()).get());
		assertEquals(String.valueOf(db.getUserId()), resource.getUserRef());
		assertEquals(String.valueOf(db.getProjectId()), resource.getProjectRef());
		assertEquals(db.getAction(), resource.getActionType());
		assertEquals(String.valueOf(db.getId()), resource.getActivityId());
	}
}