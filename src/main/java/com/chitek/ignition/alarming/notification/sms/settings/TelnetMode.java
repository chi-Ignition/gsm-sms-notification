package com.chitek.ignition.alarming.notification.sms.settings;

import java.util.Locale;

import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.i18n.Localized;

public enum TelnetMode implements Localized {
	Binary, Text;

	public String toString(Locale locale) {
		return BundleUtil.get().getStringLenient(locale, "GsmSmsNotificationSettings.enums.TelnetMode." + name());
	}

	public String toString() {
		return name();
	}
}
