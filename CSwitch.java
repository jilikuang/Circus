import java.net.InetAddress;
import java.util.HashMap;
import java.util.Properties;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.*;
import java.nio.channels.*;

public class CSwitch {
	final static String dest = "destID";
    final static String length = "length";
    static int selfID;
    static int selfIP;
    static int selfudpport;
    static InetAddress controladd;
    static int controlport;
    
    HashMap<Integer, HashMap<Integer, HashMap<Integer, Properties>>> circuit_table;// keep all circuit information within this switch
    //(srcID, (length_in, (TDID, Properties(destID, length_out))))
    
    public CSwitch (int switchID) { //, int dataport, int controlport) {
    	CircusConfig cc = CircusConfig.getConfig ();
    	selfID = switchID;
    	controladd = cc.getCntlAddr();
    	controlport = cc.getCntlPort();
    	selfudpport = cc.getSwPort(switchID);
        Circus.log ("Switch " + selfID + "is up!" );
    	new Thread (new UDPListen ()).start();
    	new Thread (new TCPListen ()).start();
        
    }
    
    public boolean insertcircuit(int srcID, int inlength, int TDID, int destID, int outlength){
        Circus.log ("Switch " + selfID + " inserting circuit from" +srcID +" to "+destID);
        HashMap<Integer, HashMap<Integer, Properties>> src_table = circuit_table.get(srcID);
        if (src_table == null) {
        	src_table = new HashMap<Integer, HashMap<Integer, Properties>>();
        	circuit_table.put(srcID, src_table);
        }
        HashMap<Integer, Properties> wlength_table = src_table.get(inlength);
        if (wlength_table == null) {
        	wlength_table = new HashMap<Integer, Properties>();
        	src_table.put(srcID, wlength_table);
        }
        Properties destinfo = wlength_table.get(TDID);
        if (destinfo == null){
        	destinfo = new Properties ();
        	String temp = ""+destID;
        	destinfo.setProperty(dest, temp);
        	temp = ""+outlength;
        	destinfo.setProperty(length, temp);
        	wlength_table.put(TDID, destinfo);//add entry
    		return true;
        }
        else {
        	String temp = ""+destID;
        	String temp1 = ""+outlength;
        	if (destinfo.get(dest).equals(temp) && destinfo.get(length).equals(temp1)){
        		return true;//entry already exist
        	}
            Circus.log ("Switch " + selfID + " Error: multiple destination while inserting circuit from" +srcID +" to "+destID );
            return false;//entry conflict
            
        }
    }
    
    public boolean removecircuit(int srcID, int inlength, int TDID){
        HashMap<Integer, HashMap<Integer, Properties>> src_table = circuit_table.get(srcID);
        HashMap<Integer, Properties> wlength_table = src_table.get(inlength);
        Properties destinfo = wlength_table.get(TDID);
        if (destinfo ==  null){
            Circus.log ("Switch " + selfID + " removes circuit: src" +srcID +" length: "+inlength+" circuit not exsit! ");
    		return false;
        }
        
        else{
            Circus.log ("Switch " + selfID + " removes circuit: src" +srcID +" length: "+inlength+" succeed! ");
        	wlength_table.remove(TDID);//remove circuit info
        	return true;
        }
    }
    
    public boolean updatepkt(CPacket pkt){
    	int swtichfrom = pkt.getFromSw();
    	int swtichto = pkt.getToSw();
    	int length = pkt.getLambda();
    	int TDID = pkt.getTdmId();
    	if(swtichto != selfID){// if this pkt is not sent to self, return false
    		return false;
    	}
        HashMap<Integer, HashMap<Integer, Properties>> src_table = circuit_table.get(swtichfrom);
        HashMap<Integer, Properties> wlength_table = src_table.get(length);
        Properties destinfo = wlength_table.get(TDID);
        if (destinfo ==  null){// if flow can not be found
            Circus.log ("Switch " + selfID + "Error: flow not found" );
     		return false;
        }
        pkt.setFromSw(selfID);
        pkt.setToSw(Integer.parseInt(destinfo.getProperty(dest)));
        pkt.setLambda(Integer.parseInt(destinfo.getProperty("length")));
        Circus.log ("Switch " + selfID + " forwarding pkt to "+ dest);
        return true;
    }
    
    public class UDPListen implements Runnable{
    	public void run(){
            final int SIZE = 8192;                    //
            byte buffer[] = new byte[SIZE];            //
        	try{
                DatagramSocket socket = new DatagramSocket(selfudpport);         //
                while(true){
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);                                    //
                    CPacket pkt = CPacket.receive(packet);
                    if (updatepkt(pkt) ==  true){
                        //deliver pkt!!!
                        CPacket.transmit(pkt);
                        Circus.log ("Switch " + selfID + " delivered a pkt.");
                    };
                    //call
                }
        	}catch(Exception e){
        		
        	}
    	}
    }
    
    
    public class TCPListen implements Runnable{
    	public void run(){
        	try{
                Socket TCPsocket = new Socket(controladd, controlport);     // initiate a socket to connect to the server
                BufferedReader br = new BufferedReader(new InputStreamReader(TCPsocket.getInputStream()));
                while(true){
                    String command=br.readLine();
                    if(!command.equals(null)){
                        //parse the TCP command
                	}
            	}
        	}catch(Exception e){
        		
        	}
    	}
    }
}

