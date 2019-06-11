package com.chitek.ignition.alarming.notification.sms;

import org.apache.log4j.Logger;

import com.chitek.ignition.alarming.notification.sms.properties.ProfileProperties;
import com.inductiveautomation.ignition.alarming.AlarmNotificationContext;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.sqltags.model.TagProviderMeta;

import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.services.ModuleServiceConsumer;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.ProviderConfiguration;

public class ModuleHook extends AbstractGatewayModuleHook implements ModuleServiceConsumer {

	private static final String MODULE_ID = "chitek.alarming.GsmSmsNotification";
	private static final String TAG_PROVIDER_NAME = "SmsNotificationStatus";
	
	private GatewayContext gatewayContext;
	private AlarmNotificationContext alarmNotificationContext;
	
	private ManagedTagProvider statusTagProvider;

	@Override
	public void setup(GatewayContext gatewayContext) {
		this.gatewayContext = gatewayContext;
		
        ProviderConfiguration configuration = new ProviderConfiguration(TAG_PROVIDER_NAME);
        configuration.setAllowTagCustomization(false);
        configuration.setPersistTags(false);
        configuration.setPersistValues(false);
        configuration.setAttribute(TagProviderMeta.FLAG_HAS_OPCBROWSE, false);

        statusTagProvider = gatewayContext.getTagManager().getOrCreateManagedProvider(configuration);
		
		// Register class with BundleUtil for localization
		// We use our own prefix 'chi_sms' to avoid naming conflicts
		BundleUtil.get().addBundle("chi_sms", getClass(), "SmsNotification");		
	}

	@Override
	public void startup(LicenseState licenseState) {
		// Register extended tag properties
		gatewayContext.getAlarmManager().registerExtendedConfigProperties(MODULE_ID, ProfileProperties.CUSTOM_SMS_MESSAGE);

		gatewayContext.getModuleServicesManager().subscribe(AlarmNotificationContext.class, this);
	}

	@Override
	public void shutdown() {
		// Unregister extended tag properties
		gatewayContext.getAlarmManager().unregisterExtendedConfigProperties(MODULE_ID);
		
		gatewayContext.getModuleServicesManager().unsubscribe(AlarmNotificationContext.class, this);
		
		if (alarmNotificationContext != null) {
			try {
				alarmNotificationContext.getAlarmNotificationManager().removeAlarmNotificationProfileType(new SmsNotificationProfileType(null));
			} catch (Exception e) {
				Logger.getLogger(SmsNotification.LOGGER_NAME).error("Exception while removing AlarmNotificationProfileType", e);
			}
		}
		
		try {
			// Shutdown tag provider
			statusTagProvider.shutdown(true);
		} catch (Exception e) {
			Logger.getLogger(SmsNotification.LOGGER_NAME).error("Error stopping ManagedTagProvider example module.", e);
		}
		
		// Remove localization
		BundleUtil.get().removeBundle("chi_sms");
	}
	
	@Override
	public void serviceReady(Class<?> serviceClass) {
		
		if (serviceClass == AlarmNotificationContext.class) {
			alarmNotificationContext = (AlarmNotificationContext) gatewayContext.getModuleServicesManager().getService(AlarmNotificationContext.class);

			try {
				alarmNotificationContext.getAlarmNotificationManager().addAlarmNotificationProfileType(new SmsNotificationProfileType(statusTagProvider));
			} catch (Exception e) {
				Logger.getLogger(SmsNotification.LOGGER_NAME).error("Exception while adding AlarmNotificationProfileType", e);
			}
		}
	}

	@Override
	public void serviceShutdown(Class<?> serviceClass) {
		
		if (serviceClass == AlarmNotificationContext.class) {
			alarmNotificationContext = null;
			Logger.getLogger(SmsNotification.LOGGER_NAME).debug("AlarmNotificationContext has been shut down");
		}
	}
	
	public boolean isFreeModule() {
		return true;
	}
}
