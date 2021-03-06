package com.chitek.ignition.alarming.notification.sms;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;

import com.chitek.ignition.alarming.notification.sms.SmsAckHandler.AckResult;
import com.chitek.ignition.alarming.notification.sms.modem.InboundMessage;
import com.chitek.ignition.alarming.notification.sms.modem.ModemDriver;
import com.chitek.ignition.alarming.notification.sms.modem.ModemEventHandler;
import com.chitek.ignition.alarming.notification.sms.modem.ModemException;
import com.chitek.ignition.alarming.notification.sms.modem.OutboundMessage;
import com.chitek.ignition.alarming.notification.sms.properties.ProfileProperties;
import com.chitek.ignition.alarming.notification.sms.settings.GsmSmsNotificationSettings;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.inductiveautomation.ignition.alarming.common.notification.NotificationProfileProperty;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfile;
import com.inductiveautomation.ignition.alarming.notification.AlarmNotificationProfileRecord;
import com.inductiveautomation.ignition.alarming.notification.NotificationContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus.State;
import com.inductiveautomation.ignition.common.TypeUtilities;
import com.inductiveautomation.ignition.common.alarming.AlarmEvent;
import com.inductiveautomation.ignition.common.config.FallbackPropertyResolver;
import com.inductiveautomation.ignition.common.expressions.ExpressionParseContext;
import com.inductiveautomation.ignition.common.expressions.parsing.Parser;
import com.inductiveautomation.ignition.common.expressions.parsing.StringParser;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.common.model.ApplicationScope;
import com.inductiveautomation.ignition.common.model.values.QualifiedValue;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataQuality;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.sqltags.model.types.TagType;
import com.inductiveautomation.ignition.common.user.ContactInfo;
import com.inductiveautomation.ignition.common.user.ContactType;
import com.inductiveautomation.ignition.common.util.AuditStatus;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.audit.AuditProfile;
import com.inductiveautomation.ignition.gateway.audit.AuditRecordBuilder;
import com.inductiveautomation.ignition.gateway.expressions.AlarmEventCollectionExpressionParseContext;
import com.inductiveautomation.ignition.gateway.expressions.FormattedExpressionParseContext;
import com.inductiveautomation.ignition.gateway.localdb.persistence.PersistenceSession;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.sqltags.simple.SimpleTagProvider;

public class SmsNotification implements AlarmNotificationProfile, ModemEventHandler {

	public static final String LOGGER_NAME = "alarming.notificaton.GsmSmsNotification";
	/** Time (milliseconds) to wait before trying to reconnect */
	static final int RECONNECT_INTERVAL = 10000;
	/** Interval (milliseconds) for checking the modem connection by sending a heartbeat */
	static final int HEARTBEAT_INTERVAL = 10000;
	/** How often to retry sending a notification if an error occurs*/
	static final int MAX_RETRIES = 3;
	/** Interval (milliseconds) for retrying to send notification */
	static final int RETRY_INTERVAL = 5000;
	
	// Tag paths
	static final String TAG_IS_CONNECTED = "/ConnectedToModem";
	static final String TAG_NETWORK_CONNECTED = "/NetworkConnected";
	static final String TAG_OPERATOR = "/NetworkOperator";
	static final String TAG_SIGNAL_LEVEL = "/SignalLevel";
		
	static final String EVENT_SEND = "send sms";
	static final String EVENT_ACK = "ack by sms";
	static final String SUCCESS = "SUCCESS";
	static final String FAILURE = "FAILURE";
	static final String ALARMING = "Alarming";
	
	private LoggerEx log;
	private GatewayContext context;

	private GsmSmsNotificationSettings settings;
	private String profileName;
	private String auditProfileName;
	
	private ModemDriver modem;
	private SmsAckHandler ackHandler;
	
	private final Object modemLock = new Object();

	/** The tag provider for modem status */
	SimpleTagProvider statusTagProvider;
	
	/** A single thread executor used for modem operations */
	private ScheduledExecutorService  executor;
	private ScheduledFuture<?> heartbeatSchedule;
	private ScheduledFuture<?> connectionSchedule;
	
	private int signalLevel = 0;
	private String operator = "";
	private ProfileStatus status;
	private boolean networkConnectionOk = false;
	private boolean stopped;
	private boolean isShutdown;
	
	public SmsNotification(GatewayContext context, AlarmNotificationProfileRecord profileRecord, GsmSmsNotificationSettings settings, SimpleTagProvider statusTagProvider) {
		this.context = context;
		this.log = LoggerEx.newBuilder().build(String.format("%s[%s]", LOGGER_NAME, profileRecord.getName()));
		log.debug("Profile created");
	    this.settings = settings;
	    this.profileName = profileRecord.getName();
	    
	    // Read the AuditProfile name - We have to use a session for reading referenced records
	    PersistenceSession ps = null;
	    ps = context.getPersistenceInterface().getSession(settings.getDataSet());
	    this.auditProfileName = settings.getAuditProfileName();
	    ps.close();
	    
	    // Init the profile status
	    this.status = ProfileStatus.UNKNOWN;

	    // A single thread executor is used here, because we want to execute modem operations sequentially
	    executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() 
	    {
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, String.format("GsmSmsNotification[%s]", profileName));
			}
	    });
	    
	    // In two-way mode, we need to handle acknowledgments
	    if (settings.isTwoWayEnabled()) {
	    	ackHandler = new SmsAckHandler(context, profileRecord);
	    	// The AckHandler gets no information if an AlarmEvent is cleared or acknowledged somewhere else, so we
	    	// have to remove stale events periodically.
	    	// This task will be removed when the executor is shut down.
	    	executor.scheduleAtFixedRate(
	    		new Runnable() {
					@Override
					public void run() {
						ackHandler.removeStaleNotifications();
					}
	    		
	    	}, 2, 2, TimeUnit.MINUTES);
	    }
	    
	    // The modem driver
		modem = new ModemDriver(settings, profileRecord.getName(), log);
		
		// Add the status tags
		this.statusTagProvider = statusTagProvider;
		initStatusTags();
	}

	private void initStatusTags() {
		statusTagProvider.configureTag(profileName + TAG_IS_CONNECTED, DataType.Boolean, TagType.Custom);
		statusTagProvider.configureTag(profileName + TAG_NETWORK_CONNECTED, DataType.Boolean, TagType.Custom);		
		statusTagProvider.configureTag(profileName + TAG_OPERATOR, DataType.String, TagType.Custom);		
		statusTagProvider.configureTag(profileName + TAG_SIGNAL_LEVEL, DataType.Int2, TagType.Custom);
		
		setStatusTagsNotConnected(false);
	}
	
	@Override
	public String getName() {
		return profileName;
	}

	@Override
	public Collection<NotificationProfileProperty<?>> getProperties() {
		Collection<NotificationProfileProperty<?>> props = new ArrayList<NotificationProfileProperty<?>>(1);		
		
		props.add(ProfileProperties.SMS_MESSAGE);
		props.add(ProfileProperties.SMS_MESSAGE_CONSOLIDATED);
		props.add(ProfileProperties.TEST_MODE);
		return props;
	}

	@Override
	public ProfileStatus getStatus() {
		return status;
	}

	@Override
	public Collection<ContactType> getSupportedContactTypes() {
		return Arrays.asList(ContactType.SMS);
	}

	@Override
	public void onShutdown() {
		if (isShutdown) {
			return;
		}
		
		log.debugf("onShutdown");
		cancelSchedule();
		executor.shutdown();
		try {
			modem.removeEventHandler();
			modem.disconnect();
		} catch (Exception e) {
			log.error("Exception while disconnecting from modem", e);
		}
		isShutdown = true;
		
		statusTagProvider.removeTag(profileName + TAG_IS_CONNECTED);
	}

	@Override
	public void onStartup() {
		
		log.debugf("onStartup");

		scheduleConnect(true);
	}

	
	@Override
	public void sendNotification(final NotificationContext notificationContext) {
		
		if(stopped || isShutdown) {
			notificationContext.notificationFailed(new LocalizedString("failed.stopped"));
			return;			
		}
		
		if (modem.isConnected()) {
			executor.execute(new NotificationTask(notificationContext));
		} else {
			executor.schedule(new NotificationTask(notificationContext), RECONNECT_INTERVAL, TimeUnit.MILLISECONDS);
		}
	}
	
	/**
	 * Parse the message expression and return the message to send.<br />
	 * If the given NotificationContext contains more then one AlertEvent, the consolidated message is used.<br/>
	 * If there is only one AlertEvent, the custom message defined in the alarm or the default message defined in the
	 * notification block is used.
	 * 
	 * @param notificationContext
	 * @return
	 * 	The parsed message.
	 */
	private String createMessage(NotificationContext notificationContext) {
		
		List<AlarmEvent> alarmEvents = notificationContext.getAlarmEvents();
		
		String message;
		if (alarmEvents.size() > 1) {
			// More than one AlarmEvent (happens only when consolidation is enabled). Use consolidated message
			message = notificationContext.getOrDefault(ProfileProperties.SMS_MESSAGE_CONSOLIDATED);
		} else {
			message = alarmEvents.get(0).get(ProfileProperties.CUSTOM_SMS_MESSAGE);
			if (StringUtils.isBlank(message)) {
				// No custom message - use the default message set in the notification block
				message = notificationContext.getOrDefault(ProfileProperties.SMS_MESSAGE);
			}
		}
		
		
		AlarmEventCollectionExpressionParseContext ctx = new AlarmEventCollectionExpressionParseContext(
			new FallbackPropertyResolver(context.getAlarmManager().getPropertyResolver()), notificationContext.getAlarmEvents());

		ExpressionParseContext parseContext = new FormattedExpressionParseContext(ctx);
		
		Parser parser = new StringParser();

		String parsed = message;
		try {
			QualifiedValue value = parser.parse(ctx.expandCollectionReferences(message), parseContext).execute();
			if (value.getQuality().isGood()) {
				parsed = TypeUtilities.toString(value.getValue());
			}
		} catch (Exception e) {
			log.error(String.format("Error parsing expression \"%s\".", message), e);
		}
		
		return parsed;
	}

	/**
	 * 
	 * @param rawNumber
	 * @return
	 * 	The phone number, normalized to E164 format.
	 * @throws NumberParseException
	 */
	private String normalizePhoneNumber(final String rawNumber) throws NumberParseException {
		PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();
		String regionCode = phoneUtil.getRegionCodeForCountryCode(settings.getCountryCode());
		PhoneNumber phoneNumber = phoneUtil.parse(rawNumber, regionCode);
		return phoneUtil.format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
	}
	
	private void writeAuditRecord(List<AlarmEvent> events, String actor, String action, boolean success) {
		if (StringUtils.isEmpty(auditProfileName)) {
			// No profile selected - do nothing
			return;
		}
		
		try {
			AuditProfile profile = context.getAuditManager().getProfile(auditProfileName);
			for (AlarmEvent event : events) {
				AuditRecordBuilder builder = new AuditRecordBuilder();
				builder.setAction(action);
				builder.setActionTarget(event.getSource().extend("evt", event.getId().toString()).toString());
				builder.setActionValue(success ? SUCCESS : FAILURE);
				builder.setActor(actor);
				builder.setActorHost(profileName);
				builder.setOriginatingContext(ApplicationScope.GATEWAY);
				builder.setOriginatingSystem(ALARMING);
				builder.setStatusCode(success ? AuditStatus.GOOD.getRawValue() : AuditStatus.BAD.getRawValue());
				builder.setTimestamp(new Date());
				profile.audit(builder.build());
			}
		} catch (Exception e) {
			log.errorf("Exception while writing audit record: ", e.getMessage());
		}
	}
	
	/**
	 * Abort execution due to a critical error
	 * @param messageKey
	 */
	private void stop(String messageKey, Object...messageParams) {
		// Close connection
		log.debug("Module stopped due to critical error.");
		status = new ProfileStatus(State.Errored, new LocalizedString(messageKey, messageParams));
		try {
			modem.disconnect();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			stopped = true;
		}
	}
	
	private void scheduleConnect(boolean immediate) {
		if (isShutdown)
			return;
		
		log.debug("New connection schedule started.");
		synchronized (modemLock) {
			if (connectionSchedule == null || connectionSchedule.isDone()) {
				connectionSchedule = executor.schedule(new Runnable() {
					@Override
					public void run() {
						doConnect();
					}
				}, immediate ? 20 : RECONNECT_INTERVAL, TimeUnit.MILLISECONDS);
			} else {
				log.debug("Connection schedule not started, another schedule is already pending.");
			}
		}
	}
	
	private void scheduleHeartbeat() {
		if (isShutdown)
			return;
		
		log.debug("New heartbeat schedule started.");
		heartbeatSchedule = executor.schedule(new Runnable() {
			@Override
			public void run() {
				doHeartbeat();
			}
		}, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
	}
	
	/**
	 * Cancel pending scheduled tasks
	 */
	private void cancelSchedule() { 
		log.debugf("Cancel schedule got GatewayLock");
		if (heartbeatSchedule != null && !heartbeatSchedule.isDone()) {
			heartbeatSchedule.cancel(false);
			heartbeatSchedule = null;
			log.debug("heartbeatSchedule cancelled");
		}

		if (connectionSchedule != null && !connectionSchedule.isDone()) {
			connectionSchedule.cancel(false);
			connectionSchedule = null;
			log.debug("connectionSchedule cancelled");
		}
	}
	
	private void doConnect() {
		if (isShutdown) {
			return;
		}
		
		synchronized (modemLock) {
			connectionSchedule = null;
			try {
				modem.setEventHandler(this);
				modem.connect();
				status = new ProfileStatus(State.Errored, new LocalizedString("chi_sms.status.waitForNetwork"));
				statusTagProvider.updateValue(profileName + TAG_IS_CONNECTED, true, DataQuality.GOOD_DATA);
				scheduleHeartbeat();
			} catch (ModemException e) {
				// SMS Gateway could not be started (e.g a wrong SIM-Pin or an invalid setup
				log.errorf("Unable to init modem - Module stopped - %s", e.getLocalizedMessage());
				stop("chi_sms.error.ModemException", e.getLocalizedMessage());
			} catch (ConnectException e) {
				// SMS Gateway could not connect to modem
				log.debugf("Could not connect to modem. " + e.getMessage());
				status = new ProfileStatus(State.Errored, new LocalizedString("chi_sms.error.noModem"));
				scheduleConnect(false);
			} catch (Exception e) {
				log.errorf("Exception while initializing modem connection: %s", e.getMessage());
				scheduleConnect(false);
			}
		}
	}
	
	/**
	 * This task checks the modem connection by sending a heartbeat and checking the result.
	 */
	private void doHeartbeat() {
		if (isShutdown || stopped) {
			return;
		}

		synchronized (modemLock) {
			heartbeatSchedule = null;
			try {
				if (!modem.isConnected()) {
					throw new IOException("Modem not connected");
				}
				updateModemStatus();
				scheduleHeartbeat();
			} catch (IOException e) {
				log.errorf("Modem connection faulted: %s", e.getMessage());
				status = new ProfileStatus(State.Errored, new LocalizedString("chi_sms.error.noModem"));
				if (modem.isConnected()) {
					modem.disconnect();
				}
				setStatusTagsNotConnected(false);
				scheduleConnect(false);	
			}
		}
	}

	/**
	 * Check the modems network connection and update network operator and signal level.
	 * @throws IOException
	 */
	private void updateModemStatus() throws IOException {
		
		synchronized (modemLock) {
			int newSignalLevel = 0;
			String newOperator;

			int regStatus = modem.getNetworkRegistration();
			if (regStatus == 1 || regStatus == 5) {
				// 1 - Home network, 5 - Roaming
				newSignalLevel = modem.getSignalLevel();
				newOperator = modem.getOperator();
				statusTagProvider.updateValue(profileName + TAG_NETWORK_CONNECTED, true, DataQuality.GOOD_DATA);
				networkConnectionOk = true;
			} else {
				status = new ProfileStatus(State.Errored, new LocalizedString("chi_sms.status.waitForNetwork"));
				setStatusTagsNotConnected(true);
				return;
			}

			log.tracef("Modem connection ok. Signal: %ddb - Operator: %s", newSignalLevel, newOperator);
			
			if (newSignalLevel != this.signalLevel || !newOperator.equals(this.operator)) {
				// Update the status only if there are changes
				this.signalLevel = newSignalLevel;
				this.operator = newOperator;
				status = new ProfileStatus(State.Good, new LocalizedString("chi_sms.status.connected", operator, newSignalLevel));
				statusTagProvider.updateValue(profileName + TAG_OPERATOR, operator, DataQuality.GOOD_DATA);
				statusTagProvider.updateValue(profileName + TAG_SIGNAL_LEVEL, newSignalLevel, DataQuality.GOOD_DATA);
			}
		}
	}
	
	/**
	 * Set the status tags to 'not connected' state
	 * 
	 * @param isConnected
	 * 	The state of the network connection to the modem
	 */
	private void setStatusTagsNotConnected(boolean isConnected) {
		networkConnectionOk = false;
		this.signalLevel = 0;
		this.operator = "";		
		statusTagProvider.updateValue(profileName + TAG_IS_CONNECTED, isConnected, DataQuality.GOOD_DATA);
		statusTagProvider.updateValue(profileName + TAG_NETWORK_CONNECTED, false, DataQuality.GOOD_DATA);
		statusTagProvider.updateValue(profileName + TAG_OPERATOR, operator, DataQuality.GOOD_DATA);
		statusTagProvider.updateValue(profileName + TAG_SIGNAL_LEVEL, signalLevel, DataQuality.GOOD_DATA);
	}

	@Override
	public void messageReceived(InboundMessage message) {
		String phoneNumber;
		try {
			phoneNumber = normalizePhoneNumber(message.getOriginatingAddress());
		} catch (NumberParseException e) {
			log.errorf("Exception while parsing phone number of inbound message: %s", e.getMessage());
			return;
		}
		log.debugf("Inbound message received from %s Text: %s", phoneNumber, message.getText());
		if (settings.isTwoWayEnabled()) {
			AckResult result = ackHandler.smsReceived(phoneNumber, message.getText());
			if (result != null) {
				writeAuditRecord(result.getEvents(), result.getUserPath().toString(), EVENT_ACK, true);
			}
		} else {
			log.warnf("Received an message from %s, but two-way mode is not enabled.", phoneNumber);
		}
	}
	
	private class NotificationTask implements Runnable {

		private final NotificationContext notificationContext;
		private int retries = 0;
		private LocalizedString error;
		
		private String phoneNumber;
		private String message;
		
		public NotificationTask(NotificationContext notificationContext) {
			this.notificationContext = notificationContext;
		}
		
		@Override
		public void run() {
			
			if (retries == 0) {
				boolean success = init();
				if (!success) {
					log.debugf("Initialisation of notification failed. Notification is not send.");
					writeAuditRecord(notificationContext.getAlarmEvents(), notificationContext.getUser().getPath().toString(), EVENT_SEND, false);
					return;
				}
			}
			
			// In test mode we just write a log entry
	        boolean testMode = (notificationContext.getOrDefault(ProfileProperties.TEST_MODE)).booleanValue();
	        if (testMode) {
	          log.infof("THIS PROFILE IS RUNNING IN TEST MODE. The following sms WOULD have been sent to %s\nMessage: %s", new Object[] { phoneNumber, message });
	          notificationContext.notificationDone();
	          return;
	        }
			
			retries ++;
			
			if (modem.isConnected() && networkConnectionOk) {
				boolean success = send();
				if (success) {
					notificationContext.notificationDone();
					return;
				}
				if (retries < MAX_RETRIES) {
					executor.schedule(this, RETRY_INTERVAL, TimeUnit.MILLISECONDS);
				} else {
					notificationContext.notificationFailed(error);
				}
			} else {
				if (retries < MAX_RETRIES) {
					if (modem.isConnected()) {
						log.debugf("Notification failed, modem not connected to GSM network. Scheduling send retry");
						executor.schedule(this, heartbeatSchedule!=null?heartbeatSchedule.getDelay(TimeUnit.MILLISECONDS) + 2000:HEARTBEAT_INTERVAL,	TimeUnit.MILLISECONDS);
					} else {
						log.debugf("Notification failed, not connected to modem. Scheduling send retry");
						executor.schedule(this, connectionSchedule != null?connectionSchedule.getDelay(TimeUnit.MILLISECONDS) + 2000 : RECONNECT_INTERVAL,	TimeUnit.MILLISECONDS);
					}
				} else {
					
					if (modem.isConnected()) {
						log.debugf("Send notification failed. Not connected to modem.");
						notificationContext.notificationFailed(new LocalizedString("chi_sms.failed.notConnected"));
					} else {
						log.debugf("Send notification failed. Modem not connected to GSM network.");
						notificationContext.notificationFailed(new LocalizedString("chi_sms.failed.noNetwork"));
					}
				}
			}
		}
		
		public boolean init() {
			String userPath = notificationContext.getUser().getPath().toString();
			
			try {
				ContactInfo smsContactInfo = null;
				for (ContactInfo ci : notificationContext.getUser().getContactInfo()) {
					if (ci.getContactType().equals(ContactType.SMS.getContactType())) {
						smsContactInfo = ci;
						break;
					}
				}
				if (smsContactInfo == null) {
					notificationContext.notificationFailed(new LocalizedString("chi_sms.failed.noSmsContactInfo", userPath));
					throw new Exception(String.format("Notification failed. No SMS contact info for user %s", userPath));
				}
				
				try {
					phoneNumber = normalizePhoneNumber(smsContactInfo.getValue());
				} catch (NumberParseException e1) {
					notificationContext.notificationFailed(new LocalizedString("chi_sms.failed.invalidPhoneNumber", userPath));
					throw new Exception(String.format("Notification failed. Invalid phone number %s for user %s", smsContactInfo.getValue(), userPath));
				}
				
				message = createMessage(notificationContext);
				if (message == null || message.isEmpty()) {
					notificationContext.notificationFailed(new LocalizedString("chi_sms.failed.noMessage"));
					throw new Exception("Notification failed. No message to send.");
				}
				
				// Register the notification for acknowledgment (if two-way mode is enabled)
				if (settings.isTwoWayEnabled()) {
					message = ackHandler.registerEvent(notificationContext, phoneNumber, message);
				}
					
				return true;
			} catch (Exception e) {
				log.error(e.getMessage());
			}
			
			return false;
		}
		
		/**
		 * Send the notification
		 * 
		 * @return
		 * 	<code>true</code> when the notification was send. If the notification was not send, the field error will contain a message.
		 */
		public boolean send() {
			String userPath = notificationContext.getUser().getPath().toString();
			log.debug("sendNotification starting for user: " + userPath);
			
			try {		        
				// Send the message					
				if (log.isTraceEnabled()) {
					log.tracef("Sending notification to %s. Text: %s", phoneNumber, message);
				} else {
					log.debugf("Sending notification to %s", phoneNumber);
				}

				OutboundMessage msg = new OutboundMessage(phoneNumber, message);	
				modem.sendMessage(msg);
				log.debugf("Message sent successfully. Ref Nr.: %d", msg.getMsgRef());
				return true;
			} catch (ModemException e) {
				error = e.getLocalizedString();
			} catch (IOException e) {
				error = new LocalizedString("chi_sms.failed.IOException");
			} catch (Exception e) {
				error = new LocalizedString("chi_sms.failed.Exception");
				log.error(e.getMessage());
			}
			
			return false;
		}
		
	}
}
