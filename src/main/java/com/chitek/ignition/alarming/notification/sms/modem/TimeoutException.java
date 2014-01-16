package com.chitek.ignition.alarming.notification.sms.modem;

/**
 * This Exception is thrown when a modem response is not received during the defined timeout.
 *
 */
public class TimeoutException extends Exception {
	public TimeoutException() {
		super("Timeout while waiting for modem response");
	}
}
