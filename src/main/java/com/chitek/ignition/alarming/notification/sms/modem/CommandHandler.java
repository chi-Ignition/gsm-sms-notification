package com.chitek.ignition.alarming.notification.sms.modem;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;

public class CommandHandler {

	protected static final long WAIT_TIME = 200;
	protected static final long READ_TIMEOUT = 1000;
	protected static final long WAIT_RESET = 2000;
	protected static final long OUTBOUND_SEND_TIMEOUT = 10000;
	
	private ModemDriver driver;

	public CommandHandler(ModemDriver driver) {
		this.driver = driver;
	}
	
	/**
	 * Disable modem echo
	 * 
	 */
	public void echoOff() {
		try {
			driver.sendCommand("ATE0");
		} catch (IOException e1) {
		}
		doWait();
		try {
			driver.clearBuffer();
		} catch (IOException e) {
		}
	}
	
	public boolean enterPin(String pin) throws IOException {
		return driver.sendCommand(String.format("AT+CPIN=\"%s\"", pin)).isOk();
	}
	
	public String getOperator() throws IOException {
		ModemResponse response = driver.sendCommand("AT+COPS?");
		if (!response.isOk() || !response.getResponse().startsWith("+COPS:"))
			return "?";
		
		String op = response.getResponse();
		int start = op.indexOf("\"");
		int end = op.lastIndexOf("\"");
		if (start > -1 && end > -1) {
			return op.substring(start+1, end);
		}
		return "?";
	}
	
	public int getNetworkRegistration() throws IOException {
		ModemResponse response = driver.sendCommand("AT+CREG?");
		if (!response.isOk() || !response.getResponse().startsWith("+CREG:")) {
			driver.getLogger().warnf("Received invalid repsonse for +CREG?: %s", response.getResponse());
			return 0;
		}
		Matcher m = ResponsePattern.CREG_RESPONSE.getPattern().matcher(response.getResponse());
		if (m.matches()) {
			int reg = Integer.parseInt(m.group(2));
			driver.getLogger().tracef("Evaluated network registration: %d", reg);
			// 1 - Home network, 5 - Roaming
			return reg;
		} else {
			driver.getLogger().debugf("Received invalid +CREG response: %s", response.getDebugString());
			return 0;
		}
	}
	
	public ModemResponse getSimStatus() throws IOException {
		return driver.sendCommand("AT+CPIN?");
	}
	
	public int getSignalLevel() throws IOException {
		ModemResponse response = driver.sendCommand("AT+CSQ");
		if (!response.isOk() || !response.getResponse().startsWith("+CSQ:")) {
			driver.getLogger().debugf("Received invalid +CSQ response: %s", response.getResponse());
			return 0;
		}
		Matcher m = ResponsePattern.CSQ_RESPONSE.getPattern().matcher(response.getResponse());
		if (m.matches()) {
			int level = Integer.parseInt(m.group(1));
			return (-113 + 2 * level);
		} else {
			driver.getLogger().debugf("Received invalid +CSQ response: %s", response.getResponse());
			return 0;
		}
	}
	
	/**
	 * Acknowledge an unsolicited response
	 * @return
	 */
	public boolean sendAcknowledge() throws IOException{
		ModemResponse response = driver.sendCommand("AT+CNMA");
		if (response.isOk()) {
			return true;
		} else {
			// Modem reported an error, try to re-enable notification
			driver.getLogger().errorf("Unsolicited response acknowledgment failed. Modem reponse: %s", response.getErrorMessage());
			driver.getLogger().debugf("Trying to reenable notifications");
			setIndications();
			return false;
		}
		
	}
	
	/**
	 * Send a SMS
	 * 
	 * @param pdu
	 * 	The pdu to send as a String
	 * @param pduSize
	 * 	Size of message in octects, excluding SMSC data.
	 * @return
	 * 	The message id
	 */
	public int sendMessage(String pdu, int pduSize) throws IOException, ModemException {
		ModemResponse response = driver.sendCommandWithPdu(String.format("AT+CMGS=%d", pduSize), pdu);
		if (!response.isOk()) {
			throw new ModemException("chi_sms.ModemException.smsError", response.getErrorMessage());
		}
		Matcher m = ResponsePattern.CMGS_RESPONSE.getPattern().matcher(response.getResponse());
		if (m.matches()) {
			driver.getLogger().debugf("Received +CMGS response Ref: %d - %s", Integer.parseInt(m.group(1)), response.getDebugString());
			return Integer.parseInt(m.group(1));
		} else {
			driver.getLogger().errorf("Received invalid +CMGS response: %s", response.getDebugString());
			return 0;
		}
	}
	
	public boolean setIndications() throws IOException {
		// Ask modem for supported indication modes
		ModemResponse response;
		response = driver.sendCommand("AT+CNMI=?");
		
		if (!response.isOk()) {
			driver.getLogger().errorf("Error while querying CNMI cababilities from modem: %s", response.getResponse());
			return false;
		}
		Matcher m = ResponsePattern.CNMI_RESPONSE.getPattern().matcher(response.getResponse());
		if (m.matches() && m.groupCount()==5) {
			// Mode
			// 0: buffer in TA;
			// 1: discard indication and reject new SMs when TE-TA link is reserved; otherwise forward directly;
			// 2: buffer new Sms when TE-TA link is reserved and flush them to TE after reservation; otherwise forward directly to the TE;
			// 3: forward directly to TE
			List<Integer> modes = GSMModemUtil.ExpandRangeResponse(m.group(1));
			int mode = 0;
			if (modes.contains(3)) {
				mode = 3;
			} else if (modes.contains(2)) {
				mode = 2;
			} else {
				driver.getLogger().warn("Modem does not support new message indications.");
				return false;
			}
			
			// mt
			// 0: no SMS-DELIVER are routed to TE;
			// 1: +CMTI: <mem>,<index> routed to TE;
			// 2: for all SMS_DELIVERs except class 2: +CMT: .... routed to TE; class 2 is indicated as in <mt>=1;
			// 3: Class 3: as in <mt>=2 other classes: As in <mt>=1;
			List<Integer> supported_mt = GSMModemUtil.ExpandRangeResponse(m.group(2));
			int mt = 0;
			if (supported_mt.contains(2)) {
				mt = 2;
			} else {
				driver.getLogger().warnf("Modem does not support direct new message indications. (mt=%s)", m.group(2));
				return false;
			}
			
			// We are not interested in Cell Broadcast's
			int bm = 0;
			
			// ds:
			// 0: No SMS-STATUS-REPORT are routed to TE;
			// 1: SMS-STATUS-REPORTs are routed to TE, using +CDS: ...
			// 2: SMS-STATUS-REPORTs is stored in memory and indicated with +CDSI: <mem>,<index>
//			List<Integer> supported_ds = GSMModemUtil.ExpandRangeResponse(m.group(4));
			// We are not interested in status reports
			int ds = 0;
//			if (supported_ds.contains(1)) {
//				ds = 1;
//			} else if (supported_ds.contains(2)) {
//				ds = 2;
//			} else {
//				driver.getLogger().warnf("Modem does not support status report indications. (ds=%s)", m.group(4));
//				return false;
//			}
			
			// Always report buffered messages
			int bfr = 0;
			
			String command = String.format("AT+CNMI=%d,%d,%d,%d,%d", mode, mt, bm, ds, bfr);
			response = driver.sendCommand(command);
			if (!response.isOk()) {
				driver.getLogger().errorf("Error while setting new message indications: %s", response.getDebugString());
				return false;
			} else {
				driver.getLogger().debugf("New message indications successfully set.");
			}
			return true;
 		} else {
			driver.getLogger().errorf("Unexpected response to +CNMI received: %s", response.getDebugString());
			return false;
		}
	}
	
	public boolean setPduProtocol() throws IOException {
		return driver.sendCommand("AT+CMGF=0").isOk();
	}
	
	public boolean setVerboseErrors() throws IOException {
		return driver.sendCommand("AT+CMEE=1").isOk();
	}
	
	/**
	 * Reset the modem
	 * 
	 * @throws TimeoutException
	 * @throws GatewayException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void reset() throws IOException
	{
		driver.writeDirect(Character.toString((char) 27));	// Send ESC
		doWait();
		driver.writeDirect("+++");
		doWait();
		driver.sendCommand("ATZ");
		doWait(WAIT_RESET);
		driver.clearBuffer();
	}
	
	public long getReadTimeout() {
		return READ_TIMEOUT;
	}
	
	public long getOutboundSendTimeout() {
		return OUTBOUND_SEND_TIMEOUT;
	}

	public void doWait(long time) {
		try {
			Thread.sleep(WAIT_TIME);
		} catch (InterruptedException e) {
		}		
	}
	
	public void doWait()  {
		doWait(WAIT_TIME);
	}
}
