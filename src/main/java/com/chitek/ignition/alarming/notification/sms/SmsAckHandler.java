package com.chitek.ignition.alarming.notification.sms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.NotificationContext;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.alarming.EventData;
import com.inductiveautomation.ignition.common.alarming.config.CommonAlarmProperties;
import com.inductiveautomation.ignition.common.config.PropertySetBuilder;
import com.inductiveautomation.ignition.common.sqltags.model.types.AlertAckMode;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

/**
 * This class handles acknowledgment if two-way mode is enabled.
 */
public class SmsAckHandler {
	
	public static final String LOGGER_NAME = "alarming.notificaton.GsmSmsAckHandler";
	private static final String ACK_PATTERN = ".*(\\d{4}).*";
	private static final long STALE_TIMEOUT = 120 * 60 * 1000;
		
	private LoggerEx log;
	private GatewayContext context;
	
	/** User with pending notifications. Key is the users's phone number, normalized to E164 format */
	private Map<String, NotifiedUser> userMap = new HashMap<String, NotifiedUser>();
	
	/** The RegEx pattern used to extract an ack-code from an incoming message*/
	Pattern ackPattern = Pattern.compile(ACK_PATTERN, Pattern.DOTALL);
	
	public SmsAckHandler(GatewayContext context, AlarmNotificationProfileRecord profileRecord) {
		this.context = context;
		this.log = LoggerEx.newBuilder().build(String.format("%s[%s]", LOGGER_NAME, profileRecord.getName()));
	}
	
	/**
	 * Add's the acknowlege code to the given message and stores the notification context for later acknowledgement.<br />
	 * If the given NotificationContext contains no acknowledgeable events (events with AckMode set to 'Manual') the message
	 * is returned as is, and the notification is not registered.
	 * 
	 * @param notificationContext
	 * 	The notification event to register
	 * @param phoneNumber
	 * 	The phone number used to send the SMS. Has to be given in E164 format.
	 * @param message
	 * 	The notification message. The ack code will be added to this message.
	 * @return
	 * 	The given message with added ack code.
	 */
	public String registerEvent(NotificationContext notificationContext, String phoneNumber, String message) {
	
		QualifiedPath userPath = notificationContext.getUser().getPath();
		String ackCode;
		
		synchronized (userMap) {
			NotifiedUser user = userMap.get(phoneNumber);
			if (user == null) {
				// User has not been notified before - create a new entry
				user = new NotifiedUser(userPath);
				userMap.put(phoneNumber, user);
			}
			
			if (user.getNotificationCount() > 9990) {
				// We are running out of ack codes. If there are thousands of alarms pending, something is really wrong...
				log.errorf("Two many notifications awaiting acknowledgement for user: %s", userPath.toStringSimple());
				return message;
			}
			
			ackCode = user.registerNotification(notificationContext);
		}
		
		// Add the ack code to the message
		if (ackCode != null) {
			log.debugf("Registered notification for user %s. AckCode: '%s'", userPath.toStringSimple(), ackCode);
			return BundleUtil.get().getString("chi_sms.messageWithAck", message, ackCode);
		} else {
			// No acknowledeable events given. Just return the message as is.
			log.debugf("registerEvent called with no acknowledgeable event.");
			return message;
		}
	}
	
	/**
	 * Evaluate an incoming messages and acknowledge the related notification.
	 * 
	 * @param phoneNumber
	 * 	The originating phone number. Has to be given in E164 format.
	 * @param message
	 * 	The received text.
	 */
	public AckResult smsReceived(String phoneNumber, String message) {
		synchronized (userMap) {
			NotifiedUser user = userMap.get(phoneNumber);
			if (user == null) {
				log.warnf("Received an inbound SMS from %s, but there are no pending notifications for this number.", phoneNumber);
				return null;
			}
			
			// Try to find an ack Code in the incoming text
			Matcher m = ackPattern.matcher(message);
			if (!m.matches() || m.groupCount() != 1) {
				log.warnf("Received an inbound SMS from %s, but there was no ack-code found in the message (%s).", phoneNumber, message);
				return null;			
			}
			String ackCode = m.group(1);
			
			List<AlarmEvent> ackEvents = user.acknowledgeNotification(ackCode);
			if (ackEvents == null) {
				log.warnf("Received an inbound SMS from %s, but there was no event registered for ack-code '%s'.", phoneNumber, ackCode);
				return null;
			}
			
			return new AckResult(user.getUserPath(), ackEvents);
		}
	}
	
	/**
	 * This method should be called regularly (every few minutes) to remove events that have not been acknowledget.
	 */
	public void removeStaleNotifications() {
		log.debugf("AckHandler is removing stale notifications.");
		synchronized (userMap) {
			Iterator<Entry<String, NotifiedUser>> it = userMap.entrySet().iterator();
			while (it.hasNext()) {
				Entry<String, NotifiedUser> entry = it.next();
				if (entry.getValue().removeStaleEvents() == 0) {
					// There are no events left for this user, so delete the user.
					if (log.isDebugEnabled()) {
						log.debugf("No notifications for user %s with phone nr %s. Removing user from map.", 
							entry.getValue().getUserPath().toStringSimple(), entry.getKey());
					}
					it.remove();
				}
			}
		}
	}
	
	private class NotifiedUser {
		
		/** The path unambiguously identifies a user */
		private QualifiedPath userPath;
		/** Acknowledgeable notifications sent to this user. Key is the ack code. */
		private Map<String, Notification> notifications = new HashMap<String, Notification>();
		private int lastCode;
		
		public NotifiedUser(QualifiedPath userPath) {
			this.userPath = userPath;
			lastCode = 1 + (int) (9998 * Math.random());
		}
		
		/**
		 * Register a new notification for this user and returns the ack-code. When creating the user, the code
		 * is initialized with a randon number, after that it simply increments with every call.
		 * 
		 * @param notificationContext
		 * @return
		 * 	The 4 digit ack code or null, if there are no acknowledegable events in the given list. 
		 */
		public String registerNotification(NotificationContext notificationContext) {
			
			// Create a new unused acknowledgement code
			int ackCode;
			do {
				if (lastCode == 9999) lastCode = 0;
				ackCode = lastCode++;
			} while (notifications.containsKey(String.format("%04d", ackCode)));
			lastCode = ackCode;
			
			Notification notification = getNotification(notificationContext.getAlarmEvents());
			if (notification != null) {
				String ackCodeString =  String.format("%04d", ackCode);
				notifications.put(ackCodeString, notification);
				return ackCodeString;
			} else {
				return null;
			}
		}
		
		public QualifiedPath getUserPath() {
			return userPath;
		}
		
		public int getNotificationCount() {
			return notifications.size();
		}
		
		/**
		 * Removes all events that are older than STALE_TIMEOUT
		 * @return
		 * 	The count of notifications after the cleanup.
		 */
		public int removeStaleEvents() {
			Iterator<Entry<String, Notification>> it = notifications.entrySet().iterator();
			long now = System.currentTimeMillis();
			while (it.hasNext()) {
				Entry<String, Notification> entry = it.next();
				if (now - entry.getValue().getTimestamp() > STALE_TIMEOUT) {
					it.remove();
				}
			}
			
			return getNotificationCount();
		}
		
		public List<AlarmEvent> acknowledgeNotification(String ackCode) {
			
			Notification notification = notifications.get(ackCode);
			if (notification == null) {
				return null;
			}
			
			// Remove the pending notification
			notifications.remove(ackCode);
			
			// Acknowledge all events
			if (log.isDebugEnabled()) {
				for (AlarmEvent event : notification.getEvents()) {
					log.debugf("AlarmEvent %s (%s) acknowledged by user %s", event.getId().toString(), event.getSource().toStringSimple(), userPath.toStringSimple());
				}
			}
			PropertySetBuilder ackData = new PropertySetBuilder();
			ackData.set(CommonAlarmProperties.AckUser, userPath);
			EventData ackEventData = new EventData(ackData.build());
			context.getAlarmManager().acknowledge(notification.getEventIds(), ackEventData);
			
			return notification.getEvents();
		}
		
		/**
		 * Returns a new {@link Notification} or null, if there are no acknowlegeable events in the given list.
		 * @param events
		 * 	The list of AlarmEvents to register
		 * @return
		 * 	A new Notification or null, if there are no acknowledgeable events.
		 */
		private Notification getNotification(List<AlarmEvent> events) {
			List<AlarmEvent> toAcknowledge = new ArrayList<AlarmEvent>(events.size());
			for (AlarmEvent event : events) {
				if (AlertAckMode.Manual.equals(event.getOrDefault(CommonAlarmProperties.AckMode))) {
					// Only add this event if AckMode is set to 'Manual'
					toAcknowledge.add(event);
				}
			}
			
			if (toAcknowledge.size() > 0) {
				return new Notification(toAcknowledge);
			} else {
				return null;
			}
		}
	}
	
	private class Notification {
		private List<AlarmEvent> events;
		private long timestamp;
		
		protected Notification(List<AlarmEvent> events) {
			this.events = events;
			timestamp = System.currentTimeMillis();
		}
		
		public List<AlarmEvent> getEvents() {
			return events;
		}
		
		public List<UUID> getEventIds() {
			List<UUID> ids = new ArrayList<UUID>(events.size());
			for (AlarmEvent event : events) {
				ids.add(event.getId());
			}
			return ids;
		}
		
		public long getTimestamp() {
			return timestamp;
		}
	}
	
	protected class AckResult {
		private QualifiedPath userPath;
		private List<AlarmEvent> events;
		
		public AckResult(QualifiedPath userPath, List<AlarmEvent>events) {
			this.userPath = userPath;
			this.events = events;
		}

		public QualifiedPath getUserPath() {
			return userPath;
		}

		public List<AlarmEvent> getEvents() {
			return events;
		}
	}
}
