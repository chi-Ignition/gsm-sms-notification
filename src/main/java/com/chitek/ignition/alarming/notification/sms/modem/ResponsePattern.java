package com.chitek.ignition.alarming.notification.sms.modem;

import java.util.regex.Pattern;

public enum ResponsePattern {

	OK("OK\\s", false),
	ANY_OK("[\\S\\s]*OK\\s+", false),
	ERROR_WITH_CODE("\\s*[\\p{ASCII}]*\\s*\\+(CM[ES])\\s+ERROR:\\s*(\\d+)\\s+", false),
	ERROR_PLAIN("\\s*[\\p{ASCII}]*\\s*(ERROR|NO CARRIER|NO DIALTONE)\\s", false),
	NEW_SMS("\\+CMT:\\s*[\\p{ASCII}]*\\s*\\p{Punct}\\s*(\\d+)\\s*\\n\\s*(\\p{XDigit}+)\\n", true),
	NEW_STATUS_REPORT("\\+CDS:\\s*\\d+\\s*\\n\\p{XDigit}*\\n", true),
	CNMI_RESPONSE("\\+CNMI:\\s*\\(([\\d,-]*)\\)[, ]*\\(([\\d,-]*)\\)[, ]*\\(([\\d,-]*)\\)[, ]*\\(([\\d,-]*)\\)[, ]*\\(([\\d,-]*)\\)\\s+OK\\s+", false),
	CSQ_RESPONSE("\\+CSQ:\\s*(\\d*)\\s*\\p{Punct}\\s*(\\d*)\\s*\\s+OK\\s*", false),
	CREG_RESPONSE("\\+CREG:\\s*(\\d+)\\s*\\p{Punct}\\s*(\\d+).*\\s+OK\\s*", false),
	CMGS_RESPONSE("\\s*\\+CMGS:\\s*(\\d+)\\s+OK\\s*", false);
	
	private Pattern pattern;
	private String regex;
	private boolean unsolicitedResponse;
	
	private ResponsePattern(String regex, boolean unsolicitedResponse) {
		this.regex = regex;
		this.unsolicitedResponse=unsolicitedResponse;
	}
	
	public Pattern getPattern() {
		if (pattern == null) {
			pattern = Pattern.compile(regex);
		}
		return pattern;
	}
	
	/**
	 * @see java.util.regex.Matcher.matches()
	 * 
	 * @param input
	 * 	The String to be matched
	 * @return
	 */
	public boolean matches(String input) {
		return getPattern().matcher(input).matches();
	}
	
	public boolean isUnsolicitedResponse() {
		return unsolicitedResponse;
	}
}
