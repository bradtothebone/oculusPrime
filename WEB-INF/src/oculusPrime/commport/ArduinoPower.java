package oculusPrime.commport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.TooManyListenersException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import oculusPrime.Application;
import oculusPrime.AutoDock;
import oculusPrime.ManualSettings;
import oculusPrime.Settings;
import oculusPrime.State;
import oculusPrime.Util;
import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;
import gnu.io.SerialPortEvent;
import gnu.io.SerialPortEventListener;

//import java.util.regex.Matcher;
//import java.util.regex.Pattern;

public class ArduinoPower implements SerialPortEventListener  {

	public static final int SETUP = 4000;
	public static final int DEAD_TIME_OUT = 10000;
	public static final int WATCHDOG_DELAY = 5000;
	public static final String FIRMWARE_ID = "oculusPower";
	
	protected Application application = null;
	protected static State state = State.getReference();
	protected SerialPort serialPort = null;	
	protected OutputStream out = null;
	protected InputStream in = null;
	
	protected volatile boolean isconnected = false;
//	protected long lastSent = System.currentTimeMillis();
	protected long lastRead;
	
//	protected final String portname = state.get(State.values.powerport);
	
	protected byte[] buffer = new byte[256];
	protected int buffSize = 0;
	protected static Settings settings = Settings.getReference();
	protected String portname = settings.readSetting(ManualSettings.powerport);

	
	public ArduinoPower(Application app) {
		application = app;	
		state.put(State.values.powerport, portname);
		
		if(powerReady()){
			
			Util.log("attempting to connect to port "+portname, this);
			
//			new Thread(new Runnable() {
//				public void run() {
					if (!isconnected) {
						connect();
						Util.delay(SETUP);
					}
					if(isconnected){
						
						Util.log("Connected to port: " + state.get(State.values.powerport), this);
						initialize();
					}
//				}
//			}).start();

			
		}
		
	}
	
	public void initialize() {
		
		Util.debug("initialize", this);
		
		registerListeners();
		lastRead = System.currentTimeMillis();
		new WatchDog(state).start();
		
	}
	
	public void connect() {
		try {

			serialPort = (SerialPort) CommPortIdentifier.getPortIdentifier(portname).open(ArduinoPrime.class.getName(), SETUP);
			serialPort.setSerialPortParams(115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

			// open streams
			out = serialPort.getOutputStream();
			in = serialPort.getInputStream();

			isconnected = true;

		} catch (Exception e) {
			
			Util.log("can't connect to port: " + e.getMessage(), this);
			
		}
	}
	
	protected void registerListeners() {
		try {
			serialPort.addEventListener(this);
		} catch (TooManyListenersException e) {
			e.printStackTrace();
		}
		serialPort.notifyOnDataAvailable(true);

	}
	
	/** inner class to check if getting responses in timely manor */
	public class WatchDog extends Thread {
		
		public WatchDog(oculusPrime.State state) {
			this.setDaemon(true);
		}

		public void run() {
			Util.delay(SETUP);
			while (true) {

				if (System.currentTimeMillis() - lastRead > DEAD_TIME_OUT) {
					state.set(oculusPrime.State.values.batterylife, "TIMEOUT");
					application.message("battery PCB timeout", "battery", "timeout");
					close();
					Util.delay(SETUP);
					connect();
				}
				
				Util.delay(WATCHDOG_DELAY);
			}		
		}
	}
	
	@Override
	public void serialEvent(SerialPortEvent event) {
		if (event.getEventType() == SerialPortEvent.DATA_AVAILABLE) {
			manageInput();
		}
	}

	/** */
	public void manageInput(){
		try {
			byte[] input = new byte[32];
			int read = in.read(input);
			for (int j = 0; j < read; j++) {
				// print() or println() from arduino code
				if ((input[j] == '>') || (input[j] == 13) || (input[j] == 10)) {
					// do what ever is in buffer
					if (buffSize > 0) execute();
					buffSize = 0; // reset
					// track input from arduino
					lastRead = System.currentTimeMillis();
				} else if (input[j] == '<') {
					// start of message
					buffSize = 0;
				} else {
					// buffer until ready to parse
					buffer[buffSize++] = input[j];
				}
			}
		} catch (IOException e) {
			System.out.println("event : " + e.getMessage());
		}
	}

	public static boolean powerReady(){
		final String power = state.get(State.values.powerport); 
		if(power == null) return false; 
		if(power.equals(Discovery.params.disabled.name())) return false;
		if(power.equals(Discovery.params.discovery.name())) return false;
		
		return true;
	}
	
	
	/** respond to feedback from the device  */	
	public void execute() {
		String response = "";
		for (int i = 0; i < buffSize; i++)
			response += (char) buffer[i];
		
		if(ArduinoPrime.DEBUGGING) Util.debug("serial in: " + response, this);
		
		if(response.equals("reset")) {
			application.message(this.getClass().getName() + "arduinOculusPower board reset", null, null);
		} 
	
		if(response.startsWith("battery")){
			String s = response.split(" ")[1];
			if (s.equals("timeout")) {
				state.put(State.values.dockstatus, AutoDock.UNKNOWN);
				state.put(State.values.batterylife, s);
				application.message(null, "battery", s);
				return;
			}
			
			if (s.equals("docked")) {
				if (!state.get(State.values.dockstatus).equals(AutoDock.DOCKED)) {
					application.message(null, "dock", AutoDock.DOCKED);
					state.put(State.values.dockstatus, AutoDock.DOCKED);
					state.set(State.values.batterycharging, true);
				}
				if (state.getBoolean(State.values.motionenabled)) {
					state.set(State.values.motionenabled, false); }
			}
			
			if (s.equals("undocked")) {
				if (!state.get(State.values.dockstatus).equals(AutoDock.UNDOCKED) &&
						!state.getBoolean(State.values.autodocking)) {
					state.put(State.values.dockstatus, AutoDock.UNDOCKED);
					state.put(State.values.batterylife, "draining");
					application.message(null, "multiple", "dock "+AutoDock.UNDOCKED+" battery draining");
					state.set(State.values.batterycharging, false);
				}
				if (!state.getBoolean(State.values.motionenabled)) { 
					state.set(State.values.motionenabled, true); }
			}
			
			String battinfo = response.split(" ")[2];
			battinfo = battinfo.replaceFirst("\\.\\d*", "");
			if (!state.get(State.values.batterylife).equals(battinfo)) {
				state.put(State.values.batterylife, battinfo);
			
			}

			String extinfo = response.split(" ")[3];
			if (!state.exists(State.values.batteryinfo.toString()) || 
						!state.get(State.values.batteryinfo).equals(extinfo)) {

				state.put(State.values.batteryinfo, extinfo);
	
				// extract sysvolts '_sV:'
			    Pattern pat = Pattern.compile("_sV:\\d+\\.\\d+");
			    Matcher mat = pat.matcher(extinfo);
			    while (mat.find()) {
			    	String sysvolts = mat.group().replaceFirst("_sV:", "");
			    	if (!state.exists(State.values.sysvolts.toString()) || 
							!state.get(State.values.sysvolts).equals(sysvolts)) {
			    		state.put(State.values.sysvolts, sysvolts);
			    	}
			    	break;
			    }
			    
			}
			
		}

	}
	
	public boolean isConnected() {
		return isconnected;
	}
	
	/** Close the serial port streams */
	private void close() {
		
		try {
			if (in != null) in.close();
		} catch (Exception e) {
			Util.log("input stream close() error " + e.getMessage(), this);
		}
		try {
			if (out != null) out.close();
		} catch (Exception e) {
			Util.log("output stream close() error" + e.getMessage(), this);
		}
		
		if (serialPort != null) {
			Util.debug("close port: " + serialPort.getName() + " baud: " + serialPort.getBaudRate(), this);
			serialPort.removeEventListener();
			serialPort.close();
			serialPort = null;
		}
		


	}
}
