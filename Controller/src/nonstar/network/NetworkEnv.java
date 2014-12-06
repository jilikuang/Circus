package nonstar.network;

import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Vector;

import nonstar.basic.CSwitch;
import nonstar.basic.Flow;
import nonstar.basic.Link;
import nonstar.basic.PSwitch;
import nonstar.basic.Switch;
import nonstar.interpreter.NetworkTopo;
import CircusCommunication.CircusComm;
import CircusCommunication.CircusCommConst;
import CircusCommunication.CircusCommObj;
import CircusPPacket.PPacket;

public class NetworkEnv implements NetworkTopo {
	HashMap<Integer, Switch> mapIdSwitch;
	HashMap<Link, Flow> mapLinkFlow;
	HashMap<Switch, HashMap<Integer, Integer>> mapSwPortSwId;

	public static void log (String s) {
		System.out.println ("[NetEnv] " + s);
	}

	public NetworkEnv() {
		super();
		mapIdSwitch = new HashMap<Integer, Switch>();
		mapLinkFlow = new HashMap<Link, Flow>();
		mapSwPortSwId = new HashMap<Switch, HashMap<Integer, Integer>>();
	}

	public synchronized boolean addSwitch(CircusCommObj cco, ObjectOutputStream oos) {
		if (cco == null || oos == null)
			return false;
		if (mapIdSwitch.containsKey(cco.getSender()))
			return false;

		Switch sw;
		Vector<Integer> list;
		HashMap<Integer, Integer> mapPortSwId;

		if (cco.getSwType() == CircusCommConst.msw_pcSwitch)
			sw = new PSwitch(oos);
		else
			sw = new CSwitch(oos);

		sw.setId(cco.getSender());
		mapIdSwitch.put(cco.getSender(), sw);

		/* Store switch connection map */
		list = cco.getConnMap();
		mapPortSwId = new HashMap<Integer, Integer>();
		for (int i = 0; i < list.size(); i++) {
			int nbrSwId = list.get(i);
			if (nbrSwId < 0)
				continue;
			mapPortSwId.put(i, nbrSwId);
			Switch nbrSw = mapIdSwitch.get(nbrSwId);
			if (nbrSw != null) {
				HashMap<Integer, Integer> nbrPortMap = mapSwPortSwId.get(nbrSw);
				Iterator<Integer> iter = nbrPortMap.keySet().iterator();
				while (iter.hasNext()) {
					int nbrPort = iter.next();
					Switch inverseSw = mapIdSwitch.get(nbrPortMap.get(nbrPort));
					if (inverseSw == sw) {
						connectSwitch(sw, i, nbrSw, nbrPort);
					}
				}
			}
		}
		mapSwPortSwId.put(sw, mapPortSwId);

		return true;
	}

	public synchronized boolean addHost(int swId, String ip) {
		Switch sw = mapIdSwitch.get(swId);

		if (sw == null)
			return false;

		if (sw instanceof PSwitch) {
			PSwitch psw = (PSwitch)sw;

			try {
				InetAddress objIp = InetAddress.getByName(ip);

				return psw.addIp(objIp);
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
		} else {
			log("The Switch is not a PSwitch");
			return false;
		}
	}

	private Switch getSwitchByIp(InetAddress ip) {
		Switch sw = null;
		Iterator<Integer> iter = mapIdSwitch.keySet().iterator();

		while (iter.hasNext()) {
			Switch tmpSw = mapIdSwitch.get(iter.next());

			if (tmpSw instanceof PSwitch) {
				PSwitch psw = (PSwitch)tmpSw;

				if (psw.connectedToIp(ip)) {
					sw = psw;
					break;
				}
			}
		}

		return sw;
	}

	public synchronized boolean processCommObj(CircusCommObj cco) {
		if (cco == null)
			return false;
		if (!mapIdSwitch.containsKey(cco.getSender()))
			return false;

		switch (cco.getMsgType()) {
		case CircusCommConst.mtype_unknown_pkt:
			log("Handling unknown packet");
			Switch srcSw = mapIdSwitch.get(cco.getSender());
			PPacket ppkt = PPacket.unpack(cco.getRawPacket());
			Switch dstSw = getSwitchByIp(ppkt.getDstIp());

			if (srcSw != null && dstSw != null) {
				Flow flow = getCurrCircuit(srcSw.getId(), dstSw.getId());
				if (flow == null)
					flow = setupCircuit(srcSw.getId(), dstSw.getId());
				if (flow != null)
					if (!walkFlow(ppkt.getSrcIp(), ppkt.getDstIp(), flow))
						log("process walkFlow failed!!!");
			}

			break;
		}

		return true;
	}

	public int addSwitch(int id, Switch sw) {
		if (mapIdSwitch.containsValue(sw))
			return -1;
		log("NEaddSw:" + id + "/" + sw.toString());
		sw.setId(id);
		mapIdSwitch.put(id, sw);
		return id;
	}

	public boolean connectSwitch(Switch swL, int portL, Switch swR, int portR) {
		if (!mapIdSwitch.containsValue(swL) || !mapIdSwitch.containsValue(swR))
			return false;
		swL.connectSwitch(portL, swR);
		swR.connectSwitch(portR, swL);
		return true;
	}

	@Override
	public Flow getCurrCircuit(int src, int dst) {
		// TODO Auto-generated method stub
		/**
		 * Link is used to describe the complete link here
		 * Search src and dst in the link to find the Flow
		 */
		Switch srcSw = mapIdSwitch.get(src);
		Switch dstSw = mapIdSwitch.get(dst);
		Set<Link> linkSet = mapLinkFlow.keySet();

		for (Link l : linkSet)
			if (l.isSource(srcSw) && l.isDestination(dstSw))
				return mapLinkFlow.get(l);

		return null;
	}

	public Flow pullCircuit(int src, int dst) {
		Switch srcSw = mapIdSwitch.get(src);
		Switch dstSw = mapIdSwitch.get(dst);
		Set<Link> linkSet = mapLinkFlow.keySet();

		for (Link l : linkSet)
			if (l.isSource(srcSw) && l.isDestination(dstSw))
				return mapLinkFlow.remove(l);

		return null;
	}

	private Link establishLink(Switch srcSw, Switch dstSw) {
		Link newLink = new Link();
		int srcPort = srcSw.getOutputPort(dstSw);
		int dstPort = dstSw.getOutputPort(srcSw);
		int srcLambda = srcSw.getAvaiableLambda(srcPort);
		int dstLambda = dstSw.getAvaiableLambda(dstPort);

		while (srcLambda != dstLambda) {
			if (!dstSw.testLambda(dstPort, srcLambda))
				dstLambda = srcLambda;
			else if (!srcSw.testLambda(srcPort, dstLambda))
				srcLambda = dstLambda;
			else {
				do {
					srcLambda++;
				} while (srcSw.testLambda(srcPort, srcLambda));
				do {
					dstLambda++;
				} while (dstSw.testLambda(dstPort, dstLambda));
			}
		}

		srcSw.allocLambda(srcPort, srcLambda);
		newLink.setSwitch(srcSw, srcPort);
		dstSw.allocLambda(dstPort, dstLambda);
		newLink.setSwitch(dstSw, dstPort);
		newLink.setLambda(srcLambda);

		log("establish:" + srcSw.toString() + "/" + srcPort + "=" + srcLambda + "=" + dstSw.toString() + "/" + dstPort);
		return newLink;
	}

	private Flow getFlow(Switch srcSw, Switch dstSw) {
		HashMap<Switch, Integer> swDist = new HashMap<Switch, Integer>();
		HashMap<Switch, Switch> swPrev= new HashMap<Switch, Switch>();
		HashSet<Switch> toVisitSw = new HashSet<Switch>();

		/* Initialize tovisitSw */
		for (int i : mapIdSwitch.keySet()) {
			toVisitSw.add(mapIdSwitch.get(i));
		}
		swDist.put(srcSw, 0);

		while (!toVisitSw.isEmpty()) {
			/* Find minimum distance node */
			Switch swMin = null;
			int dist = -1;
			for (Switch sw : toVisitSw) {
				if (swDist.get(sw) != null) {
					if ((dist < 0) || (swDist.get(sw) < dist)) {
						dist = swDist.get(sw);
						swMin = sw;
					}
				}
			}

			toVisitSw.remove(swMin);
			if (swMin == dstSw)
				break;

			/* Compute neighbors */
			for (int port : swMin.getPortSet()) {
				Switch sw = swMin.getNeighborSwitch(port);
				int alt = swDist.get(swMin) + 1;
				if (((swDist.get(sw)) == null) || (alt < swDist.get(sw))) {
					swDist.put(sw, alt);
					swPrev.put(sw, swMin);
				}
			}
		}

		/* Read shortest path */
		Switch tmpSw = dstSw;
		Flow flow = new Flow();
		while (swPrev.get(tmpSw) != null) {
			flow.addLinkFirst(establishLink(swPrev.get(tmpSw), tmpSw));
			flow.addSwFirst(tmpSw);
			tmpSw = swPrev.get(tmpSw);
		}
		flow.addSwFirst(tmpSw);

		return flow;
	}

	/*private boolean walkFlow(Switch srcSw, Switch dstSw, Flow flow) {
		Iterator<Switch> iterSw = flow.getSwIter();
		Iterator<Link> iterLink = flow.getLinkIter();
		Link LinkIn = null, LinkOut = null;
		Switch tmpSw = null;

		while (iterSw.hasNext()) {
			tmpSw = iterSw.next();
			System.out.print(tmpSw + "=>");
			if (tmpSw == srcSw) {
				System.out.print("Setup in PSwitch:");
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.println("outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
			} else if (tmpSw == dstSw) {
				System.out.print("Setup out PSwitch:");
				LinkIn = LinkOut;
				System.out.println("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
			} else {
				System.out.print("Setup CSwitch:");
				LinkIn = LinkOut;
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.print("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
				System.out.println(";outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
			}
		}

		return true;
	}*/

	private boolean walkFlow(InetAddress srcIp, InetAddress dstIp, Flow flow) {
		Iterator<Switch> iterSw = flow.getSwIter();
		Iterator<Link> iterLink = flow.getLinkIter();
		Link LinkIn = null, LinkOut = null;
		Switch tmpSw = null;
		Switch srcSw = getSwitchByIp(srcIp);
		Switch dstSw = getSwitchByIp(dstIp);

		log("walkFlow from " + srcIp.toString() + " to " + dstIp.toString());

		while (iterSw.hasNext()) {
			tmpSw = iterSw.next();
			System.out.print(tmpSw + "=>");
			if (tmpSw == srcSw) {
				System.out.print("Setup in PSwitch:");
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.println("outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
				CircusComm.txAddEntry_ps_P2C(srcIp.getHostAddress(), dstIp.getHostAddress(),
						tmpSw.getNeighborSwitch(LinkOut.getPort(tmpSw)).getId(),
						LinkOut.getLambda(), 1, tmpSw.getObjOutStream());
			} else if (tmpSw == dstSw) {
				System.out.print("Setup out PSwitch:");
				LinkIn = LinkOut;
				System.out.println("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
				CircusComm.txAddEntry_ps_C2P(srcIp.getHostAddress(), dstIp.getHostAddress(),
						tmpSw.getNeighborSwitch(LinkIn.getPort(tmpSw)).getId(),
						LinkIn.getLambda(), 1, tmpSw.getObjOutStream());
			} else {
				System.out.print("Setup CSwitch:");
				LinkIn = LinkOut;
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.print("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
				System.out.println(";outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
				CircusComm.txSetup_cs(
						tmpSw.getNeighborSwitch(LinkOut.getPort(tmpSw)).getId(),
						tmpSw.getNeighborSwitch(LinkIn.getPort(tmpSw)).getId(),
						LinkIn.getLambda(),
						LinkOut.getLambda(),
						1,
						tmpSw.getObjOutStream());
			}
		}

		return true;
	}

	private boolean eraseFlow(InetAddress srcIp, InetAddress dstIp, Flow flow) {
		Iterator<Switch> iterSw = flow.getSwIter();
		Iterator<Link> iterLink = flow.getLinkIter();
		Link LinkIn = null, LinkOut = null;
		Switch tmpSw = null;
		Switch srcSw = getSwitchByIp(srcIp);
		Switch dstSw = getSwitchByIp(dstIp);

		log("eraseFlow from " + srcIp.toString() + " to " + dstIp.toString());

		while (iterSw.hasNext()) {
			tmpSw = iterSw.next();
			System.out.print(tmpSw + "=>");
			if (tmpSw == srcSw) {
				System.out.print("Remove in PSwitch:");
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.println("outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
				CircusComm.txRmEntry_ps_P2C(srcIp.getHostAddress(), dstIp.getHostAddress(),
						tmpSw.getNeighborSwitch(LinkOut.getPort(tmpSw)).getId(),
						LinkOut.getLambda(), 1, tmpSw.getObjOutStream());
			} else if (tmpSw == dstSw) {
				System.out.print("Remove out PSwitch:");
				LinkIn = LinkOut;
				System.out.println("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
				CircusComm.txRmEntry_ps_C2P(srcIp.getHostAddress(), dstIp.getHostAddress(),
						tmpSw.getNeighborSwitch(LinkIn.getPort(tmpSw)).getId(),
						LinkIn.getLambda(), 1, tmpSw.getObjOutStream());
			} else {
				System.out.print("Remove CSwitch:");
				LinkIn = LinkOut;
				if (iterLink.hasNext())
					LinkOut = iterLink.next();
				else
					log("Fatal error");
				System.out.print("inport " + LinkIn.getPort(tmpSw) + "/lambda " + LinkIn.getLambda());
				System.out.println(";outport " + LinkOut.getPort(tmpSw) + "/lambda " + LinkOut.getLambda());
				CircusComm.txTeardown_cs(
						tmpSw.getNeighborSwitch(LinkOut.getPort(tmpSw)).getId(),
						tmpSw.getNeighborSwitch(LinkIn.getPort(tmpSw)).getId(),
						LinkIn.getLambda(),
						LinkOut.getLambda(),
						1,
						tmpSw.getObjOutStream());
			}
		}

		return true;
	}

	public void setupCircuit(String srcIp, String dstIp) {
		InetAddress src, dst;
		Switch srcSw, dstSw;
		Flow flow;

		log("setupCircuit: " + srcIp + "=>" + dstIp);

		try {
			src = InetAddress.getByName(srcIp);
			dst = InetAddress.getByName(dstIp);
			srcSw = getSwitchByIp(src);
			dstSw = getSwitchByIp(dst);
			if (srcSw != null && dstSw != null) {
				flow = getCurrCircuit(srcSw.getId(), dstSw.getId());
				if (flow == null) {
					flow = setupCircuit(srcSw.getId(), dstSw.getId());
					if (flow != null) {
						if (!walkFlow(src, dst, flow))
							log("walkFlow failed!!!");
					}
				}
			} else {
				log("Cannot find src/dst Switch by IP");
			}
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void tearCircuit(String srcIp, String dstIp) {
		InetAddress src, dst;
		Switch srcSw, dstSw;
		Flow flow;

		log("setupCircuit: " + srcIp + "=>" + dstIp);

		try {
			src = InetAddress.getByName(srcIp);
			dst = InetAddress.getByName(dstIp);
			srcSw = getSwitchByIp(src);
			dstSw = getSwitchByIp(dst);
			if (srcSw != null && dstSw != null) {
				flow = pullCircuit(srcSw.getId(), dstSw.getId());
				if (flow != null) {
					if (!eraseFlow(src, dst, flow))
						log("eraseFlow failed!!!");
				}
			} else {
				log("Cannot find src/dst Switch by IP");
			}
		} catch (UnknownHostException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	public Flow setupCircuit(int src, int dst) {
		// TODO Auto-generated method stub
		/**
		 *
		 */
		if (src == dst)
			return null;

		Switch srcSw = mapIdSwitch.get(src);
		Switch dstSw = mapIdSwitch.get(dst);

		Flow flow = getFlow(srcSw, dstSw);
		if (flow != null) {
			Link newLink = new Link();
			newLink.setSource(srcSw);
			newLink.setDestination(dstSw);
			mapLinkFlow.put(newLink, flow);
		}

		/* Send control messages */
		//walkFlow(srcSw, dstSw, flow);

		return flow;
	}
}
