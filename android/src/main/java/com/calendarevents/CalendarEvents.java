package com.calendarevents;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.provider.CalendarContract;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.WritableMap;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.TimeZone;

public class CalendarEvents extends ReactContextBaseJavaModule {

    public static int PERMISSION_REQUEST_CODE = 37;
    public static String DETAILS_KEY_NOTES = "notes";
    public static String DETAILS_KEY_LOCATION = "location";
    public static String DETAILS_KEY_START_DATE = "startDate";
    public static String DETAILS_KEY_END_DATE = "endDate";
    public static String DETAILS_KEY_ALL_DAY = "allDay";

    private ReactContext reactContext;
    private static HashMap<Integer, Promise> permissionsPromises = new HashMap<>();

    public CalendarEvents(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "CalendarEvents";
    }

    //region Calendar Permissions
    private void requestCalendarReadWritePermission(final Promise promise)
    {
        Activity currentActivity = getCurrentActivity();
        if (currentActivity == null) {
            promise.reject("E_ACTIVITY_DOES_NOT_EXIST", "Activity doesn't exist");
            return;
        }
        PERMISSION_REQUEST_CODE++;
        permissionsPromises.put(PERMISSION_REQUEST_CODE, promise);
        ActivityCompat.requestPermissions(currentActivity,
                new String[]{ Manifest.permission.WRITE_CALENDAR },
                PERMISSION_REQUEST_CODE);
    }

    public static void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        if (permissionsPromises.containsKey(requestCode)) {
            // If request is cancelled, the result arrays are empty.
            Promise permissionsPromise = permissionsPromises.get(requestCode);
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionsPromise.resolve("authorized");
            } else if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                permissionsPromise.resolve("denied");
            } else if (permissionsPromises.size() == 1) { // there should only be one
                permissionsPromise.reject("permissions - unknown error", grantResults.length > 0 ? String.valueOf(grantResults[0]) : "Request was cancelled");
            }
            permissionsPromises.remove(requestCode);
        }
    }

    private boolean haveCalendarReadWritePermissions()
    {
        int permissionCheck = ContextCompat.checkSelfPermission(reactContext,
                Manifest.permission.WRITE_CALENDAR);

        return permissionCheck == PackageManager.PERMISSION_GRANTED;
    }
    //endregion

    private Date parseISODateString(String dateString) throws ParseException {
        String dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
        SimpleDateFormat sdf = new SimpleDateFormat(dateFormat);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        return sdf.parse(dateString);
    }

    private long dateStringToTimestamp(String dateString) throws ParseException {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        try {
            cal.setTime(parseISODateString(dateString));
        } catch (ParseException e) {
            e.printStackTrace();
            throw e;
        }

        return cal.getTimeInMillis();
    }

    //region Event Accessors
    public WritableMap addEvent(String title, ReadableMap details) throws ParseException {
        ContentResolver cr = reactContext.getContentResolver();
        ContentValues eventValues = new ContentValues();

        WritableMap event = Arguments.createMap();
        eventValues.put(CalendarContract.Events.CALENDAR_ID, 1);

        if (title != null) {
            eventValues.put(CalendarContract.Events.TITLE, title);
        }
        if (details.hasKey(DETAILS_KEY_NOTES)) {
            eventValues.put(CalendarContract.Events.DESCRIPTION, details.getString(DETAILS_KEY_NOTES));
        }
        if (details.hasKey(DETAILS_KEY_LOCATION)) {
            eventValues.put(CalendarContract.Events.EVENT_LOCATION, details.getString(DETAILS_KEY_LOCATION));
        }

        if (details.hasKey(DETAILS_KEY_START_DATE)) {
            eventValues.put(CalendarContract.Events.DTSTART, dateStringToTimestamp(details.getString(DETAILS_KEY_START_DATE)));
        }

        if (details.hasKey(DETAILS_KEY_END_DATE)) {
            eventValues.put(CalendarContract.Events.DTEND, dateStringToTimestamp(details.getString(DETAILS_KEY_END_DATE)));
        }

        if (details.hasKey("recurrence")) {
            String rule = createRecurrenceRule(details.getString("recurrence"));
            if (rule != null) {
                eventValues.put(CalendarContract.Events.RRULE, rule);
            }
        }
        if (details.hasKey(DETAILS_KEY_ALL_DAY)) {
            eventValues.put(CalendarContract.Events.ALL_DAY, details.getBoolean(DETAILS_KEY_ALL_DAY));
        }
        eventValues.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        if (details.hasKey("alarms")) {
            eventValues.put(CalendarContract.Events.HAS_ALARM, true);
        }

        Uri eventsUri = CalendarContract.Events.CONTENT_URI;
        Uri eventUri = cr.insert(eventsUri, eventValues);
        int eventID = Integer.parseInt(eventUri.getLastPathSegment());

        if (details.hasKey("alarms")) {
            createRemindersForEvent(cr, eventID, details.getArray("alarms"));
        }

        event.putInt("eventID", eventID);
        return event;
    }
    //endregion

    //region Reminders
    private void createRemindersForEvent(ContentResolver resolver, int eventID, ReadableArray reminders) {
        for (int i = 0; i < reminders.size(); i++) {
            ReadableMap reminder = reminders.getMap(i);
            ReadableType type = reminder.getType("date");
            if (type == ReadableType.Number) {
                int minutes = -reminder.getInt("date");
                ContentValues reminderValues = new ContentValues();

                reminderValues.put(CalendarContract.Reminders.EVENT_ID, eventID);
                reminderValues.put(CalendarContract.Reminders.MINUTES, minutes);
                reminderValues.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_DEFAULT);

                resolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues);
            }
        }
    }
    //endregion

    //region Recurrence Rule
    private String createRecurrenceRule(String recurrence) {
        String recurrenceRule = null;
        String[] validValues = {"daily", "weekly", "monthly", "yearly"};

        if (Arrays.asList(validValues).contains(recurrence)) {
            if (recurrence == "daily") {
                recurrenceRule = "FREQ=DAILY";
            } else if (recurrence == "weekly") {
                recurrenceRule = "FREQ=WEEKLY";
            } else if (recurrence == "monthly") {
                recurrenceRule = "FREQ=MONTHLY";
            } else if (recurrence == "yearly") {
                recurrenceRule = "FREQ=YEARLY";
            }
        }

        return recurrenceRule;
    }
    //endregion

    //region React Native Methods
    @ReactMethod
    public void requestCalendarPermissions(Promise promise) {
        if (this.haveCalendarReadWritePermissions()) {
            promise.resolve("authorized");
        } else {
            this.requestCalendarReadWritePermission(promise);
        }
    }

    @ReactMethod
    public void saveEvent(String title, ReadableMap details, Promise promise) {
        if (this.haveCalendarReadWritePermissions()) {
            try {
                WritableMap event = this.addEvent(title, details);
                promise.resolve(event);
            } catch (Exception e) {
                promise.reject("add event error", e.getMessage());
            }
        } else {
            promise.reject("add event error", "you don't have permissions to add an event to the users calendar");
        }
    }

    @ReactMethod
    public void sendIntentForCalendarEvent(String title, ReadableMap details) {
        Calendar cal = new GregorianCalendar();
        cal.setTime(new Date());
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setData(CalendarContract.Events.CONTENT_URI);
        intent.putExtra(CalendarContract.Events.TITLE, title);
        if (details.hasKey(DETAILS_KEY_NOTES)) {
            intent.putExtra(CalendarContract.Events.DESCRIPTION, details.getString(DETAILS_KEY_NOTES));
        }
        if (details.hasKey(DETAILS_KEY_LOCATION)) {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, details.getString(DETAILS_KEY_LOCATION));
        }
        if (details.hasKey(DETAILS_KEY_ALL_DAY)) {
            intent.putExtra(CalendarContract.Events.ALL_DAY, details.getString(DETAILS_KEY_ALL_DAY));
        }

        try {
            if (details.hasKey(DETAILS_KEY_START_DATE)) {
                intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                        dateStringToTimestamp(details.getString(DETAILS_KEY_START_DATE)));
            }
            if (details.hasKey(DETAILS_KEY_END_DATE)) {
                intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                        dateStringToTimestamp(details.getString(DETAILS_KEY_END_DATE)));
            }
        } catch (Exception e) {
            // TODO: add error handling here
            return;
        }

        getReactApplicationContext().startActivity(intent);
    }

    @ReactMethod
    public void openEventInCalendar(int eventID) {
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventID);
        Intent sendIntent = new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).setData(uri);

        if (sendIntent.resolveActivity(reactContext.getPackageManager()) != null) {
            reactContext.startActivity(sendIntent);
        }
    }

    @ReactMethod
    public void uriForCalendar(Promise promise) {
      promise.resolve(CalendarContract.Events.CONTENT_URI.toString());
    }
    //endregion
}
