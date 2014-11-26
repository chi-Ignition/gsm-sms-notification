package com.chitek.ignition.alarming.notification.sms.properties;

import java.util.ArrayList;
import java.util.List;

import com.inductiveautomation.ignition.alarming.common.notification.BasicNotificationProfileProperty;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.alarming.config.AlarmProperty;
import com.inductiveautomation.ignition.common.alarming.config.BasicAlarmProperty;
import com.inductiveautomation.ignition.common.config.CategorizedProperty;
import com.inductiveautomation.ignition.common.i18n.LocalizedString;

/**
 * The properties defined here are shared by all instances, so they are static
 * @author chi
 *
 */
public class ProfileProperties {
	
	// Profile properties
	public static final BasicNotificationProfileProperty<String> SMS_MESSAGE = 
		new BasicNotificationProfileProperty<String>("smsMessage", "chi_sms.properties.smsMessage.name", null, String.class);

	public static final BasicNotificationProfileProperty<String> SMS_MESSAGE_CONSOLIDATED = 
		new BasicNotificationProfileProperty<String>("smsMessageConsolidated", "chi_sms.properties.smsMessageConsolidated.name", null, String.class);
	
	public static final BasicNotificationProfileProperty<Boolean> TEST_MODE =
		new BasicNotificationProfileProperty<Boolean>("testMode", "chi_sms.properties.testMode.name", null, Boolean.class);

	
	// Custom alarm properties
	public static AlarmProperty<String> CUSTOM_SMS_MESSAGE = 
		new BasicAlarmProperty<String>("customSmsMessage", String.class, "", "chi_sms.properties.extendedConfig.customSmsMessage.name",
			"chi_sms.properties.extendedConfig.category", "chi_sms.properties.extendedConfig.customSmsMessage.desc", true, false);

	
	static {
		SMS_MESSAGE.setExpressionSource(true);
	    SMS_MESSAGE.setDefaultValue(BundleUtil.get().getString("chi_sms.properties.smsMessage.default"));
	    SMS_MESSAGE_CONSOLIDATED.setExpressionSource(true);
	    SMS_MESSAGE_CONSOLIDATED.setDefaultValue(BundleUtil.get().getString("chi_sms.properties.smsMessageConsolidated.default"));
	    TEST_MODE.setDefaultValue(Boolean.valueOf(false));
	    List<CategorizedProperty.Option<Boolean>> options = new ArrayList<CategorizedProperty.Option<Boolean>>();
	    // words.yes and words.no are defined in common properties
	    options.add(new CategorizedProperty.Option<Boolean>(Boolean.valueOf(true), new LocalizedString("words.yes", new Object[0])));
	    options.add(new CategorizedProperty.Option<Boolean>(Boolean.valueOf(false), new LocalizedString("words.no", new Object[0])));
	    TEST_MODE.setOptions(options);
	}

}
