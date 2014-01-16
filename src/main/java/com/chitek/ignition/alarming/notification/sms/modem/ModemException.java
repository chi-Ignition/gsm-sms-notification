package com.chitek.ignition.alarming.notification.sms.modem;

import java.util.Locale;

import com.inductiveautomation.ignition.common.i18n.LocalizedString;

public class ModemException extends Exception {

	LocalizedString message;
	int errorCode;
	
	public ModemException(String messageKey, Object... args) {
		super(messageKey);
		this.message = new LocalizedString(messageKey, args);
	}
	
	@Override
	public String getMessage() {
		// en_US is Ignition's default locale
		return message.toString(Locale.US);
	}
	
	public LocalizedString getLocalizedString() {
		return message;
	}

}
