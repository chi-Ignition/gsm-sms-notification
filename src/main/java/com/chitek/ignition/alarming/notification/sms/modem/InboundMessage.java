package com.chitek.ignition.alarming.notification.sms.modem;

import org.ajwcc.pduUtils.gsm3040.Pdu;

public class InboundMessage {
	
	private final Pdu pdu;
	
	public InboundMessage (Pdu pdu) {
		this.pdu = pdu;
	}
	
	/**
	 * Return the originating address (the senders phone number) of this message.<br />
	 * The address is always retuned exactly as it is received by the modem.
	 * @return
	 */
	public String getOriginatingAddress() {
		return pdu.getAddress();
	}
	
	public String getText() {
		return pdu.getDecodedText();
	}
	
	public String toString() {
		return pdu.toString();
	}
}
