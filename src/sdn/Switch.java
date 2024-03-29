package sdn;

import java.io.*;
import java.util.*;
import java.net.*;

public class Switch {
	private DatagramSocket socket;
	private SwitchInfo info;
	private InetAddress ControllerAddress;
	private Integer ControllerPort;
	private static final Integer wdtSleepMillis = 3000;
	//number of cycles to wait before declaring a switch dead
	private static final Integer waitCycles = 4;
	//duration beyond with a switch is declared dead
	private static final float maxDuration = wdtSleepMillis * waitCycles;
	//variable to keep track of links that are considered to be dead
	private HashSet<Integer> deadLinks;
	//logging verbosity
	private Verbosity logVerbosity;
 
	public Switch(Integer id, String ControllerAddr,Integer Cntrlport, String verbosity) throws UnknownHostException {
	   	ControllerPort = Cntrlport;
		ControllerAddress = InetAddress.getByName(ControllerAddr);
		info = new SwitchInfo();
		info.id = id;
		info.alive = true;
		deadLinks = new HashSet<Integer>();
		//set the logging verbosity
		if (verbosity.equals("HIGH")) {
			logVerbosity = Verbosity.HIGH;
		}
		else if (verbosity.equals("MEDIUM")) {
			logVerbosity = Verbosity.MEDIUM;
		}
		else {
			logVerbosity = Verbosity.LOW;
		}
		try {
		    socket = new DatagramSocket();
		}
		catch (SocketException e) {
		    e.printStackTrace();
		    System.exit(1);
		}
		}
	//nested class
	 //watchdog thread implementation
	private class WatchdogThread implements Runnable {
		 
		 public void run() {
			 
			 try {
				 
				//System.out.println("started watchdog thread");
				 log("WatchdogThread:Started watchdog thread", Verbosity.HIGH);
			    while (true) {
			     //get the current time
				     long currentTime = System.currentTimeMillis();
				     
				     
				     synchronized (info) {
				      
			      
			      //check the timestamp recorded in each switch against the current time if the switch is already alive
					      for (Integer id : info.neighbors.keySet()) {
					    	  if (info.neighbors.get(id).linkStatus.equals(true) && ((currentTime - info.neighbors.get(id).linkStatusTimestamp) > maxDuration)) {
					        //mark switch link as false if the switch has not sent a keep alive message for a long duration
							        info.neighbors.get(id).linkStatus = false;
							        log("WatchdogThread: Marked link to the following switch as unavailable:"+id.toString(),Verbosity.LOW);
							        log("WatchdogThread: Sending topology update after link from switch "+info.id.toString() + " to " + id.toString() + " is marked dead",Verbosity.LOW);
						        
					    	  }
						       else {
						    	   //check  the neighboring switch info and send a keep alive if there exists a link
						    	   	if (info.neighbors.get(id).ipAddress != null && info.neighbors.get(id).linkStatus.equals(true) && !deadLinks.contains(id)) {
						    	   		log("WatchdogThread: Sending Keep alive to switch:"+id.toString(),Verbosity.HIGH);
						    	   		//System.out.println("Sending keep alive to switch: " + id);
						    	   		Message msg = new Message(info.id,"KEEP_ALIVE",info);
						    	   		sendMessage(msg,id);
						    	   	}
						       }
					      }
				     }
		     
				     synchronized(info) {
				    	 log("WatchdogThread: Sending topology update",Verbosity.HIGH);
				    	 // periodic topology update messages
					     //System.out.println("sending topology update");
					      Message msg = new Message(info.id, "TOPOLOGY_UPDATE", info);
					      sendMessage(msg,0);
				     }
	     
	     
	     Thread.sleep(wdtSleepMillis);
			    }
			 }
			   catch (InterruptedException e) {
			    e.printStackTrace();
			    System.exit(1);
			   }
			}
		}
	public static void main(String[] argv) throws IOException {
		 Switch switc = new Switch(Integer.valueOf(argv[0]), argv[1], Integer.valueOf(argv[2]), argv[3]);
		 if (argv.length > 4 && argv[4].equals("-f")) {
			 for (int i=5; i<argv.length; i++) {
				 switc.deadLinks.add(Integer.valueOf(argv[i]));
			 }
		 }
		 switc.bootstrap();
		 Switch.WatchdogThread wdt = switc.new WatchdogThread();
		 Thread t = new Thread(wdt);
		 t.start();
		 while(true) {
			  Message msg = switc.receiveMessage();
			  switc.handleMessage(msg);  
		 }
	  
	}
	private void bootstrap() {
		  Message msg = new Message(info.id,"REGISTER_REQUEST", info);
		  log("bootstrap: Sending Register Request from the switch"+info.id.toString(),Verbosity.LOW);
		  sendMessage(msg,0);
		  Message mssg = receiveMessage();
		  log("bootstrap: Received Register Response from the controller",Verbosity.LOW);
		  //System.out.println("received register response");
		  //for all the neighboring links that are marked alive, set the timestamp to current time
		  for (Integer id : mssg.swInfo.neighbors.keySet()) {
			  if (mssg.swInfo.neighbors.get(id).linkStatus) {
				  mssg.swInfo.neighbors.get(id).linkStatusTimestamp = System.currentTimeMillis();
			  }
		  }
		  info = mssg.swInfo;
	}
	private void sendMessage(Message msg, Integer destid) {
		  try {
		         //create a new byte array output stream
		         ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		         //create an object output stream
		         ObjectOutputStream oos = new ObjectOutputStream(byteStream);
		         oos.writeObject(msg);
		         DatagramPacket packet;
		         if(destid.equals(0))
		         {
		          packet = new DatagramPacket(byteStream.toByteArray(), byteStream.size(),ControllerAddress,ControllerPort);
		         }
		         else {
		           packet = new DatagramPacket(byteStream.toByteArray(), byteStream.size(),info.neighbors.get(destid).ipAddress,info.neighbors.get(destid).port);
		         }
		         
		         socket.send(packet);
		  }
		   catch (IOException e) {
		             e.printStackTrace();
		             System.exit(1);
		   }
	}
	private Message receiveMessage() {
             Message msg = new Message(null, null, null);
	         //buffer to receive the incoming Message
	     byte[] buf = new byte[2048];
	 
	     try {
	             DatagramPacket packet = new DatagramPacket(buf, buf.length);
	             socket.receive(packet);
	             //create a bytearray input stream
	             ByteArrayInputStream inStr = new ByteArrayInputStream(buf);
	             //create object input stream
	             ObjectInputStream ois = new ObjectInputStream(inStr);
	             msg = (Message)ois.readObject();
	             if(msg.header.equals("KEEP_ALIVE")) {
	            	 synchronized (info) {
			               info.neighbors.get(msg.switchId).ipAddress = packet.getAddress();
			               info.neighbors.get(msg.switchId).port = packet.getPort();
	            	 }
	             }
	            
	     }
		 catch(ClassNotFoundException e) {
		         e.printStackTrace();
		         System.exit(1);
		  }
		 catch(IOException e) {
		         e.printStackTrace();
		         System.exit(1);
		 }
		
		 return msg;
	}
		 
	private void handleMessage(Message msg) {
		  if (msg.header.equals("KEEP_ALIVE")) {
			  synchronized(info) {
				  log("handleMessage: Received keep alive from switch:" + msg.switchId.toString(),Verbosity.HIGH);
				  //System.out.println("received keep alive from : " + msg.switchId);
			//check if the link was earlier declared dead (if yes, send a topology update immediately)
					if (info.neighbors.get(msg.switchId).linkStatus.equals(false)) {
						info.neighbors.get(msg.switchId).linkStatus = true;
						log("handleMessage: Link from switch "+info.id.toString() + " to " + msg.switchId + " is now alive", Verbosity.LOW);
						//System.out.println("link from switch "+ info.id + " to " + msg.switchId + " is now alive");
						log("handleMessage: Sending topology update after link from switch "+info.id.toString() + " to " + msg.switchId + " is marked alive",Verbosity.LOW);
						//System.out.println("sending topology update");
						Message topologyUpdatemsg = new Message(info.id, "TOPOLOGY_UPDATE", info);
						sendMessage(topologyUpdatemsg, 0);
						info.neighbors.get(msg.switchId).linkStatusTimestamp = System.currentTimeMillis();
					}
					else {
						info.neighbors.get(msg.switchId).linkStatusTimestamp = System.currentTimeMillis();
					}
			  }
		  }
		  else if (msg.header.equals("ROUTE_UPDATE")) {
				 synchronized(info) {
					 //received a route update and store the next hop information
						log("handleMessage: Received Route Update",Verbosity.LOW);
						log ("handleMessage: Routing table : " + msg.swInfo.nextHop.toString(), Verbosity.LOW);
						info.nextHop = msg.swInfo.nextHop;
				 }
		}
	 }
	 
	private synchronized void log(String str, Verbosity ver) {
			if (logVerbosity == Verbosity.HIGH) {
				System.out.println("[" + System.currentTimeMillis() + "]: " + str);
			}
			else if ((logVerbosity == Verbosity.MEDIUM) && (ver != Verbosity.HIGH)) {
				System.out.println("[" + System.currentTimeMillis() + "]: " + str);
			}
			else if ((logVerbosity == Verbosity.LOW) && (ver == Verbosity.LOW)) {
				System.out.println("[" + System.currentTimeMillis() + "]: " + str);
			}
			else {}
	 }
}
  
