package com.chitek.ignition.alarming.notification.sms.modem;

import java.util.regex.Matcher;

import com.inductiveautomation.ignition.common.i18n.LocalizedString;

public class ModemResponse {
	public static final int ERR_UNKNOWN = 10000;
	public static final int ERR_TIMEOUT = 10001;
	public static final int ERR_IO_EXCEPTION = 10002;
	public static final int NOT_CONNECTED = 10003;
	public static final int RESPONSE_OK = 0;
	
	private int error;
	private String response;
	private ResponsePattern responsePattern;
	
	public ModemResponse(String response) {
		this(response, RESPONSE_OK, null);
	}
	
	public ModemResponse(String response, int error) {
		this(response, error, null);
	}
	
	public ModemResponse(String response, int error, ResponsePattern responsePattern) {
		this.error = error;
		this.response = response;
		this.responsePattern = responsePattern;
	}		
	
	public String getResponse() {
		return response;
	}
	
	public Matcher getMatcher() {
		return responsePattern.getPattern().matcher(response);
	}
	
	/**
	 * @return
	 * 	The response with CR and LF replaced by readable Strings
	 */
	public String getDebugString() {
		return response.replace("\r\n", "<cr><lf>").replace("\n", "<lf>");
	}
	
	public void setError(int error) {
		this.error = error;
	}
	
	public int getError() {
		return error;
	}
	
	/**
	 * @return
	 * 	The pattern found, or null if no valid pattern was found.
	 */
	public ResponsePattern getPattern() {
		return responsePattern;
	}
	
	public LocalizedString getErrorMessage() {
		return new LocalizedString(String.format("chi_sms.modemError.%d", error));
	}
	
	public boolean isOk() {
		return error == 0;
	}
}
