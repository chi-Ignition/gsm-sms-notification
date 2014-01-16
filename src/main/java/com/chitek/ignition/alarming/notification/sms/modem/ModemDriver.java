package com.chitek.ignition.alarming.notification.sms.modem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;

import org.ajwcc.pduUtils.gsm3040.Pdu;
import org.ajwcc.pduUtils.gsm3040.PduGenerator;
import org.ajwcc.pduUtils.gsm3040.PduParser;
import org.ajwcc.pduUtils.gsm3040.SmsDeliveryPdu;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.SimpleOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;

import com.chitek.ignition.alarming.notification.sms.settings.GsmSmsNotificationSettings;
import com.chitek.ignition.alarming.notification.sms.settings.TelnetMode;
import com.inductiveautomation.ignition.common.util.LoggerEx;

public class ModemDriver {

	private final LoggerEx log;

	private static final String READER_THREAD_NAME = "GsmModemReader[%s]";
	private static final String NOTIFIER_THREAD_NAME = "GsmModemNotifier[%s]";
	
	// Settings
	private final String settingsPin;
	private final String settingsCsca;
	private final String settingsHostAddress;
	private final int settingsPort;
	private final TelnetMode settingsTelnetMode;
	private final boolean settingsTwoWayEnabled;
	private final String profileName;

	protected final CommandHandler handler;
	private final PduGenerator pduGenerator;
	private final PduParser pduParser;

	// Telnet Client
	private TelnetClient telnetClient;
	private OutputStream os;
	private TerminalTypeOptionHandler ttopt = new TerminalTypeOptionHandler("VT100", false, false, true, false);
	private SimpleOptionHandler binaryopt = new SimpleOptionHandler(0, true, false, true, false);
	private EchoOptionHandler echoopt = new EchoOptionHandler(true, false, true, false);
	private SuppressGAOptionHandler gaopt = new SuppressGAOptionHandler(true, true, true, true);
	
	private Reader reader;
	private Notifier notifier;

	/** Lock used when a modem response is expected */
	private Lock readerLock = new ReentrantLock();
	private Condition dataAvailable = readerLock.newCondition();

	private volatile boolean isConnected;
	private volatile ModemEventHandler modemEventHandler;

	public ModemDriver(GsmSmsNotificationSettings settings, String profileName, LoggerEx log) {
		this.log = log;
		this.profileName = profileName;
		this.settingsPin = settings.getSimPin();
		this.settingsCsca = settings.getCsca();
		this.settingsTelnetMode = settings.getTelnetMode();
		this.settingsPort = settings.getPort();
		this.settingsHostAddress = settings.getHostAddress();
		this.settingsTwoWayEnabled = settings.isTwoWayEnabled();

		this.handler = new CommandHandler(this);
		this.pduGenerator = new PduGenerator();
		this.pduParser = new PduParser();
	}

	public void connect() throws ModemException, ConnectException, IOException {
		ModemResponse response;
		try {
			connectTelnet();
			isConnected = true;

			clearBuffer();
			handler.reset();
			handler.echoOff();
			handler.setVerboseErrors();

			while (true) {
				response = handler.getSimStatus();
				while (response.getResponse().indexOf("BUSY") >= 0) {
					log.debugf("SIM is busy, waiting...");
					handler.doWait();
					response = handler.getSimStatus();
				}
				if (response.getResponse().indexOf("SIM PIN2") >= 0) {
					// Modem request the SIM PIN2 - we don't support that
					log.errorf("SIM requesting PIN2. Response: %s", response.getDebugString());
					throw new ModemException("chi_sms.ModemException.simPin2Required");
				} else if (response.getResponse().indexOf("SIM PIN") >= 0) {
					log.debugf("SIM requesting PIN. Response: %s", response.getDebugString());
					if (StringUtils.isEmpty(settingsPin))
						throw new ModemException("chi_sms.ModemException.pinRequired");
					if (!handler.enterPin(settingsPin))
						throw new ModemException("chi_sms.ModemException.pinNotAccepted");
					handler.doWait();
					continue;
				} else if (response.getResponse().indexOf("READY") >= 0) {
					break;
				} else if (response.getError() > 0) {
					log.debugf("Erroneous CPIN response: %s", response.getDebugString());
					throw new ModemException("chi_sms.ModemException.cpinResponseError", response.getErrorMessage());
				}
				log.warnf("Cannot understand SIMPIN response: %s, will wait for a while...", response.getDebugString());
				handler.doWait();
			}

			if (!handler.setIndications() && settingsTwoWayEnabled) {
				log.error("Callback indications were *not* set succesfully. SMS acknowledging will not be possible!");
			}

			if (!handler.setPduProtocol()) {
				throw new ModemException("chi_sms.ModemException.noPdu");
			}

			isConnected = true;
		} catch (IOException e) {
			disconnect();
			throw e;
		}
	}

	
	public void setEventHandler(ModemEventHandler eventHandler) {
		this.modemEventHandler = eventHandler;
	}
	
	public void removeEventHandler() {
		this.modemEventHandler = null;
	}
	
	/**
	 * @return <code>true</code> if the modem is connected and initialized
	 */
	public boolean isConnected() {
		return isConnected;
	}
	
	public synchronized int getNetworkRegistration() throws IOException {
		readerLock.lock();
		try {
			return handler.getNetworkRegistration();
		} finally {
			readerLock.unlock();
		}
	}
	
	public synchronized String getOperator() throws IOException {
		readerLock.lock();
		try {
			return handler.getOperator();
		} finally {
			readerLock.unlock();
		}
	}

	public synchronized int getSignalLevel() throws IOException {
		readerLock.lock();
		try {
			return handler.getSignalLevel();
		} finally {
			readerLock.unlock();
		}
	}
	
	/**
	 * Send the given Outbound Message
	 * @param message
	 * 	The message to send
	 */
	public synchronized boolean sendMessage(OutboundMessage message) throws IOException, ModemException{
		
		// Create a randon multi-part id
		int mpRefNo = (int)(Math.random()*65535);
		// Make the list of pdu's - More than one PDU is created for multi-part messages
		List<String>pdus = pduGenerator.generatePduList(message.getPdu(settingsCsca), mpRefNo);
		for (String pdu : pdus) {
			// The pdu size must not include the length of the service center address
			int pduSize = pdu.length() / 2;
			if (settingsCsca.isEmpty()) {
				// Reduce length if no service center address is set
				pduSize--;
			} else {
				int smscNumberLen = settingsCsca.length();
				if (settingsCsca.charAt(0) == '+') smscNumberLen--;
				if (smscNumberLen % 2 != 0) smscNumberLen++;
				int smscLen = (2 + smscNumberLen) / 2;
				pduSize = pduSize - smscLen - 1;
			}
			if (log.getLogger().isTraceEnabled()) {
				log.tracef("Sending PDU:\r%s" , new PduParser().parsePdu(pdu).toString());
			}
			
			readerLock.lock();
			try {
				int msgRef = handler.sendMessage(pdu, pduSize);
				message.setMsgRef(msgRef);
			} catch (ModemException e) {
				log.errorf("Error sending message: %s", e.getMessage());
				throw e;
			} catch (IOException e) {
				log.errorf("IOException while sending message: %s", e.getMessage());
				throw e;
			} finally {
				readerLock.unlock();
			}
		}
		
		return true;
	}

	/**
	 * Acknowledge an unsolicited response
	 */
	public synchronized void sendAcknowledge() {
		readerLock.lock();
		try {
			log.debug("Sending new mesage acknowledgement to modem.");
			handler.sendAcknowledge();
		} catch (IOException e) {
			log.errorf("IOException while acknowledging unsolicited response: %s", e.getMessage());
		} finally {
			readerLock.unlock();
		}
	}
	
	/**
	 * Close the modem connection
	 */
	public void disconnect() {
		log.debugf("Closing connection.");
		isConnected = false;
		if (reader != null) {
			reader.stop();
		}
		if (notifier != null) {
			notifier.interrupt();
			try {
				log.debugf("Waiting for notifier thread.");
				notifier.join();
			} catch (InterruptedException e) {
			}
			notifier = null;
		}
		try {
			telnetClient.disconnect();
		} catch (Exception e) {
		}
	}

	private void connectTelnet() throws IOException, ConnectException, ModemException {
		log.debugf("Connecting to: %s:%d", settingsHostAddress, settingsPort);

		if (telnetClient == null) {
			try {
				telnetClient = new TelnetClient();
				telnetClient.addOptionHandler(this.ttopt);
				telnetClient.addOptionHandler(this.echoopt);
				telnetClient.addOptionHandler(this.gaopt);
				if (settingsTelnetMode.equals(TelnetMode.Binary)) {
					telnetClient.addOptionHandler(this.binaryopt); // Make telnet session binary, so ^Z in Sendmessage is send raw!
				}
				// telnetClient.setReaderThread(true);
			} catch (InvalidTelnetOptionException e) {
				throw new ModemException("invalidTelnetOption");
			}
		}

		telnetClient.connect(settingsHostAddress, settingsPort);

		os = telnetClient.getOutputStream();
		reader = new Reader(telnetClient.getInputStream());
		Thread thread = new Thread(reader);
		thread.setName(String.format(READER_THREAD_NAME, profileName));
		thread.start();
		
		notifier = new Notifier();
		notifier.setName(String.format(NOTIFIER_THREAD_NAME, profileName));
		notifier.start();
		
		isConnected = true;
	}
	
	/**
	 * Error in modem connection. Close and reinitialize.
	 */
	private void error(String message, Object... params) {
		log.errorf(message, params);
		disconnect();
	}

	/**
	 * Clear the input stream
	 * 
	 * @throws IOException
	 */
	protected void clearBuffer() throws IOException {
		String buffer = null;
		
		readerLock.lock();
		try {
			buffer = reader.clearBuffer();
		} finally {
			readerLock.unlock();
			if (buffer != null) {
				log.debugf("clearBuffer() received %d byte: %s", buffer.length(), buffer);
			} else {
				log.debugf("Buffer was empty");
			}
		}
	}
	
	/**
	 * Send a command to the modem and return the response. The responsePattern \r ist appended to the given command.
	 * 
	 * @param command
	 * @return
	 */
	protected ModemResponse sendCommand(String command) throws IOException {
		if (!isConnected)
			return new ModemResponse("", ModemResponse.NOT_CONNECTED);
		
		readerLock.lock();
		try {
			try {
				write(command + "\r");
			} catch (IOException e) {
				error("IOException while writing to modem: %s", e.getMessage());
				return new ModemResponse("", ModemResponse.ERR_IO_EXCEPTION);
			}
			return getResponse();
		} finally {
			readerLock.unlock();
		}
	}
	
	protected ModemResponse sendCommandWithPdu(String command, String pdu) throws IOException {
		if (!isConnected)
			return new ModemResponse("", ModemResponse.NOT_CONNECTED);
		
		readerLock.lock();
		try {
			try {
				write(command + "\r");
			} catch (IOException e) {
				error("IOException while writing to modem: %s", e.getMessage());
				return new ModemResponse("", ModemResponse.ERR_IO_EXCEPTION);
			}
			ModemResponse response = getResponse();
			if (response.getResponse().equals(">")) {
				log.debugf("> received. Sending pdu.");
				os.write(pdu.getBytes()); 
				os.write(26); // Send ESC
				os.flush();
				return getResponse(handler.getOutboundSendTimeout());
			} else {
				return response;
			}
		} finally {
			readerLock.unlock();
		}		
	}
	
	private ModemResponse getResponse() throws IOException {
		return getResponse(handler.getReadTimeout());
	}
	
	/**
	 * Wait for a modem response and return the received response. The calling thread has to hold the
	 * readerLock!
	 * 
	 * @param timeout
	 * @return
	 * @throws IOException
	 */
	private ModemResponse getResponse(long timeout) throws IOException {
		ModemResponse response;
		int error = 0;

		try {
			if (!dataAvailable.await(timeout, TimeUnit.MILLISECONDS)) {
				throw new SocketTimeoutException("Timeout while waiting for modem response");
			}
			response = reader.getResponse();
		} catch (InterruptedException e1) {
			throw new SocketTimeoutException("Timeout while waiting for modem response");
		} 

		if (log.isDebugEnabled()) {
			log.debugf("Received modem response: %s", response.getDebugString());
		}
		
		if (response.getResponse().equals(">")) {
			// Modem waits for input
			return response;
		}

		if (response.getPattern() == ResponsePattern.ERROR_WITH_CODE) {
			// Try to interpret error code
			Matcher m = response.getMatcher();
			if (m.find()) {
				try {
					if (m.group(1).equals("CME")) {
						int code = Integer.parseInt(m.group(2));
						error = 5000 + code;
					} else if (m.group(1).equals("CMS")) {
						int code = Integer.parseInt(m.group(2));
						error = 6000 + code;
					} else {
						log.errorf("Invalid error response:", m.group(1));
						error = ModemResponse.ERR_UNKNOWN;
					}
				} catch (NumberFormatException e) {
					log.errorf("Error on number conversion while interpreting response: %s", response);
					error = ModemResponse.ERR_UNKNOWN;
				}
			} else {
				log.errorf("Received unmatched error code. Should never happen!");
				error = ModemResponse.ERR_UNKNOWN;
			}
		} else if (response.getPattern() == ResponsePattern.ERROR_PLAIN) {
			error = 9000;
		} else if (response.getResponse().indexOf("OK") == -1) {
			error = 10000;
		}
		
		if (error != 0) {
			response.setError(error);
			log.debugf("Received error %d from modem: %s", error, response.getDebugString());
		}
		return response;
	}

	private ResponsePattern findTerminator(String response) {
		for (ResponsePattern t : ResponsePattern.values()) {
			if (t.getPattern().matcher(response).matches()) {
				log.tracef("Found responsePattern: %s", t.name());
				return t;
			}
		}
		return null;
	}

	protected void writeDirect(String s) throws IOException {
		readerLock.lock();
		try {
			write(s);
		} finally {
			readerLock.unlock();
		}
	}

	private void write(String s) throws IOException {
		log.debugf("Sending message to modem: %s", s);
		os.write(s.getBytes());
		os.flush();
	}

	protected LoggerEx getLogger() {
		return log;
	}
	
	private class Reader implements Runnable {

		volatile boolean keepRunning = true;
		private InputStream is;
		private char[] buffer = new char[256];
		private int bufferPos = 0;
		private boolean newline = true;
		private ModemResponse modemResponse;
		
		public Reader(InputStream is) {
			this.is = is;
		}
		
		@Override
		public void run() {
			
			while (keepRunning) {
				try {
					int c = is.read();
					if (c == -1) {
						// Socket closed
						keepRunning = false;
						error("Socket connection closed");
						break;
					}
					
					if ((char) c == '>' && (newline || bufferPos == 0)) {
						// Modem waits for input
						log.debugf("Reader: Modem awaits input ('>' received)");
						dataAvailable(new ModemResponse(">"));
						continue;
					}
					
					if (!newline || (c != 10 && c != 13)) {
						// Add to buffer if we are not in a run of newline chars
						buffer[bufferPos ++] = (char) c;
						newline = false;
					}
					
					if (!newline && (c == 10 || c == 13)) {
						// <CR>/<LF> received - check if we have a full response
						buffer[bufferPos - 1] = 10;	// Replace <CR> with <LF>
						newline = true;
						String response = new String(buffer,0, bufferPos);
						ResponsePattern pattern = findTerminator(response);
						if (pattern != null) {
							if (pattern.isUnsolicitedResponse()) {
								handleUnsolicitedResponse(evalResponse(response, pattern));
							} else {
								dataAvailable(evalResponse(response, pattern));
							}
						} else {
							// No matching pattern found
							log.tracef("Response arrived, no matching pattern: %s", response);
						}
					}
					
				} catch (IOException e) {
					keepRunning = false;
					error("IOException in reader thread: " + e.getMessage());
				} 
			}
		
			log.debugf("Reader thread ended");
		}
	
		/**
		 * Clear any pending buffer content.
		 * 
		 * @return
		 * 	A String with the discarded buffer.
		 */
		public String clearBuffer() {
			String bufferContent;
			readerLock.lock();
			try {
				bufferContent = new String(buffer, 0, bufferPos);
				bufferPos = 0;
				newline = true;
			} finally {
				readerLock.unlock();
			}
			return bufferContent;
		}
		
		/**
		 * The last response received from the modem. Starting linebreaks and runs of newline/linefeeds are
		 * replaced by a single linefeed (10) to simplify response evaluation.<br />
		 * This method must only be called after dataAvailable is signalled.
		 * @return
		 */
		public ModemResponse getResponse() {
			return modemResponse;
		}
		
		public void stop() {
			keepRunning = false;
		}
		
		private void handleUnsolicitedResponse(ModemResponse modemResponse) {
			if (modemResponse.getPattern().equals(ResponsePattern.NEW_SMS)) {
				// New inbound SMS
				notifier.addResponse(modemResponse);
			}
			// Reset the input buffer
			bufferPos = 0;
			newline = true;
		}
		
		private void dataAvailable(ModemResponse modemResponse) {
			readerLock.lock();
			try {
				log.tracef("Data available. Response: %s", modemResponse.getDebugString());
				this.modemResponse = modemResponse;
				dataAvailable.signalAll();
				
				// Reset the input buffer
				bufferPos = 0;
				newline = true;
			} finally {
				readerLock.unlock();
			}
		}
		
		private ModemResponse evalResponse(String response, ResponsePattern pattern) {	
			return new ModemResponse(response, ModemResponse.RESPONSE_OK, pattern);
		}
		
	}
	
	private class Notifier extends Thread {

		private BlockingQueue<ModemResponse> eventQueue = new LinkedBlockingQueue<ModemResponse>();
		
		protected void addResponse(ModemResponse modemResponse)	{
			log.debugf("Storing AsyncEvent: %s", modemResponse.getPattern().name());
			this.eventQueue.add(modemResponse);
		}
		
		@Override
		public void run() {
			
			while (isConnected()) {
				try {
					ModemResponse response = eventQueue.take();
					if (ResponsePattern.NEW_SMS.equals(response.getPattern())) {
						sendAcknowledge();
						handleInboundMessage(response);
						
					}
				} catch (InterruptedException e) {
					if (!isConnected()) break;
				}
			}
			
			log.debugf("Notifier thread ended");
		}
		
		private void handleInboundMessage(ModemResponse response) {
			Matcher m =response.getMatcher();
			if (m.matches() && m.groupCount()==2) {
				int pduSize = Integer.parseInt(m.group(1));
				String pduString = m.group(2);
				
				if ((pduSize > 0) && ((pduSize * 2) == pduString.length())) {
					// The pduSize does not conatin the adress part. If the length of the pdu equals the pduSize,
					// then there is no adress part. As the Parser requires an address part, we add it here.
					pduString = "00" + pduString;
				}
				
				Pdu pdu = pduParser.parsePdu(pduString);
				if (pdu instanceof SmsDeliveryPdu) {
					InboundMessage message = new InboundMessage(pdu);
					if (log.isTraceEnabled()) {
						log.tracef("New inbound mesage:\r%s", message.toString());
					}
					if (modemEventHandler != null) {
						try {
							modemEventHandler.messageReceived(message);
						} catch (Exception e) {
							log.errorf("Exception while processing modemEvent: %s", e.getMessage());
						}
					}
				} else {
					log.errorf("Invalid inbound pdu received");
				}
				
			} else {
				log.errorf("Error parsing inbound message notification: %s", response.getDebugString());
			}
		}
		
	}

}
