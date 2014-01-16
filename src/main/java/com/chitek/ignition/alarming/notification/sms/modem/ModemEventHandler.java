package com.chitek.ignition.alarming.notification.sms.modem;

public interface ModemEventHandler {
	/**
	 * Called by the modem driver when a new inbound message is received.
	 * 
	 * @param message
	 */
	public void messageReceived(InboundMessage message);
}
