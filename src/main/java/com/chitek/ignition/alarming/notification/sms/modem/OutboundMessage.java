package com.chitek.ignition.alarming.notification.sms.modem;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduFactory;
import org.ajwcc.pduUtils.gsm3040.PduUtils;
import org.ajwcc.pduUtils.gsm3040.SmsSubmitPdu;

public class OutboundMessage {
	
	private String text;
	private String destination;
	private int msgRef;
	
	/**
	 * Createa an new outbound message with default settings.
	 * 
	 * @param destination
	 * 	The destination address
	 * @param text
	 * 	The message text
	 */
	public OutboundMessage(String destination, String text) {
		this.text = text;
		this.destination = destination;
	}
	
	public void setMsgRef(int msgRef) {
		this.msgRef = msgRef;
	}
	
	/**
	 * @return
	 * 	The reference number after sending the message
	 */
	public int getMsgRef() {
		return msgRef;
	}
	
	public Pdu getPdu(String smscNumber) {
		SmsSubmitPdu pdu = PduFactory.newSmsSubmitPdu(PduUtils.TP_VPF_NONE);
		
		// smscInfo
		// address type field + #octets for smscNumber
		// NOTE: make sure the + is not present when computing the smscInfoLength
		String smscNumberForLengthCheck = smscNumber;
		if (smscNumber.startsWith("+"))
		{
			smscNumberForLengthCheck = smscNumber.substring(1);
		}
		pdu.setSmscInfoLength(1 + (smscNumberForLengthCheck.length() / 2) + ((smscNumberForLengthCheck.length() % 2 == 1) ? 1 : 0));
		// set address
		pdu.setSmscAddress(smscNumber);
		pdu.setSmscAddressType(PduUtils.getAddressTypeFor(smscNumber));
		// set destination address
		pdu.setAddress(destination);
		pdu.setAddressType(PduUtils.getAddressTypeFor(destination));
		// Protocol ID - 0 for a standard SMS
		pdu.setProtocolIdentifier(0);
		// Encoding - 7-bit alphabet with default message class 0
		pdu.setDataCodingScheme(PduUtils.DCS_ENCODING_7BIT);
		// validity period - not used
		pdu.setValidityPeriod(-1);
		// set the text
		pdu.setDecodedText(text);
		return pdu;
	}
}
