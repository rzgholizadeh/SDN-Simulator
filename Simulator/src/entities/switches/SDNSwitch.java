package entities.switches;

import java.util.HashMap;

import entities.*;
import system.*;
import system.events.*;
import system.utility.*;

public abstract class SDNSwitch extends Node {

	public boolean isMonitored;

	public Link controlLink;
	public int controllerID = 0; // Assuming there is only 1 controller

	public HashMap<Integer, Link> accessLinks; // <HostID, Link>
	public HashMap<Integer, Link> networkLinks; // <SwitchID, Link>
	private HashMap<Integer, Integer> flowTable; // <FlowID, SwitchID(neighbors)>

	public SDNSwitch(int ID, Link controlLink) {
		super(ID, Keywords.Operations.Nodes.Names.Switch);
		this.controlLink = controlLink;
		isMonitored = true;
		accessLinks = new HashMap<Integer, Link>();
		networkLinks = new HashMap<Integer, Link>();
		flowTable = new HashMap<Integer, Integer>();
	}

	/* --------------------------------------------------- */
	/* ---------- Abstract methods ----------------------- */
	/* --------------------------------------------------- */
	public abstract Network recvCtrlMessage(Network net, CtrlMessage message);

	/* --------------------------------------------------- */
	/* ---------- Inherited methods (from Node) ---------- */
	/* --------------------------------------------------- */
	public Network recvPacket(Network net, Packet packet) {
		Segment segment = packet.segment;
		/* ================================================ */
		/* ========== Control message stays in switch ===== */
		/* ================================================ */
		if (packet.type == Keywords.Operations.Packets.Types.SDNControl) {
			return recvCtrlMessage(net, packet.controlMessage);
		}
		/* ================================================ */
		/* ========== Broadcast segment to hosts ========== */
		/* ================================================ */
		else if (segment.getDstHostID() == Keywords.Operations.BroadcastDestination) {
			return broadcastToHosts(net, segment);
		}
		/* ================================================ */
		/* ========== Segment to end host ================= */
		/* ================================================ */
		else if (isConnectedToHost(segment.getDstHostID())) {
			return forwardToHost(net, segment.getDstHostID(), segment);
		}
		/* ================================================ */
		/* ========== Segment to next switch ============== */
		/* ================================================ */
		else if (hasFlowEntry(segment.getFlowID())) {
			if (segment.getType() == Keywords.Operations.Segments.Types.UncontrolledFIN) {
				/* Uncontrolled FIN goes to controller */
				return forwardToController(net, segment);
			} else {
				return forwardToSwitch(net, getNextSwitchID(segment.getFlowID()), segment);
			}
		}
		/* ================================================ */
		/* ========== Segment to controller =============== */
		/* ================================================ */
		else {
			return forwardToController(net, segment);
		}
	}

	public Network releasePacket(Network net, int dstNodeID, Packet packet) {
		Segment segment = packet.segment;
		Event nextEvent;
		float nextTime, linkUtilizationTime;
		/* ================================================ */
		/* ========== Next node is a Host ================= */
		/* ================================================ */
		if (isConnectedToHost(segment.getDstHostID())) {
			linkUtilizationTime = accessLinks.get(dstNodeID).getTransmissionDelay(segment.getSize());
			nextTime = net.getCurrentTime() + getAccessLinkTotalDelay(dstNodeID, segment.getSize());
			nextEvent = new ArrivalToHost(nextTime, dstNodeID, packet);
			deQueueFromAccessLinkBuffer(dstNodeID);
			/** ===== Statistical Counters ===== **/
			if (isMonitored) {
				accessLinks.get(dstNodeID).updateUtilizationCounters(segment.getFlowID(), linkUtilizationTime);
			}
			/** ================================ **/
		}
		/* ================================================ */
		/* ========== Next node is a Switch =============== */
		/* ================================================ */
		else if (hasFlowEntry(segment.getFlowID())) {

			if (segment.getType() == Keywords.Operations.Segments.Types.UncontrolledFIN) {
				/* Uncontrolled FIN goes to controller */
				nextTime = net.getCurrentTime() + controlLink.getTotalDelay(segment.getSize());
				nextEvent = new ArrivalToController(nextTime, this.ID, packet);
				controlLink.buffer.deQueue();
			} else {
				linkUtilizationTime = networkLinks.get(dstNodeID).getTransmissionDelay(segment.getSize());
				nextTime = net.getCurrentTime() + getNetworkLinkTotalDelay(segment.getFlowID(), segment.getSize());
				nextEvent = new ArrivalToSwitch(nextTime, dstNodeID, packet);
				deQueueFromNetworkLinkBuffer(segment.getFlowID());
				/** ===== Statistical Counters ===== **/
				if (isMonitored) {
					networkLinks.get(getNextSwitchID(segment.getFlowID()))
							.updateUtilizationCounters(segment.getFlowID(), linkUtilizationTime);
				}
				/** ================================ **/
			}
		}
		/* ================================================ */
		/* ========== Next node is a Controller =========== */
		/* ================================================ */
		else {
			nextTime = net.getCurrentTime() + controlLink.getTotalDelay(segment.getSize());
			nextEvent = new ArrivalToController(nextTime, this.ID, packet);
			controlLink.buffer.deQueue();
		}
		net.eventList.addEvent(nextEvent);
		return net;
	}

	/* --------------------------------------------------- */
	/* ---------- Implemented methods -------------------- */
	/* --------------------------------------------------- */
	/* ########## Protected ############################## */
	protected void addFlowTableEntry(int flowID, int neighborID) {
		flowTable.put(flowID, neighborID);
	}

	protected boolean hasFlowEntry(int flowID) {
		if (flowTable.containsKey(flowID)) {
			return true;
		} else {
			return false;
		}
	}

	protected Network broadcastToHosts(Network net, Segment segment) {
		float nextTime = 0;
		float bufferTime = 0;
		for (int hostID : accessLinks.keySet()) {
			bufferTime = accessLinks.get(hostID).buffer.getBufferTime(net.getCurrentTime(),
					accessLinks.get(hostID).getTransmissionDelay(segment.getSize()));
			nextTime = net.getCurrentTime() + bufferTime;
			if (nextTime > 0) {
				segment.setDstHostID(hostID);
				Event nextEvent = new DepartureFromSwitch(nextTime, this.ID, hostID, new Packet(segment, null));
				net.eventList.addEvent(nextEvent);
			} else {
				// Packet Drop happens here
				Main.error("SDNSwitch", "broadcastToHosts", "Packe Drop happened at switch_" + ID);
			}
		}
		return net;
	}

	protected Network forwardToHost(Network net, int hostID, Segment segment) {
		float bufferTime = accessLinks.get(hostID).buffer.getBufferTime(net.getCurrentTime(),
				accessLinks.get(hostID).getTransmissionDelay(segment.getSize()));
		float nextTime = net.getCurrentTime() + bufferTime;
		if (nextTime > 0) {
			Event nextEvent = new DepartureFromSwitch(nextTime, this.ID, hostID, new Packet(segment, null));
			net.eventList.addEvent(nextEvent);
		} else {
			// Packet Drop happens here
			Main.error("SDNSwitch", "forwardToHost", "Packe Drop happened at switch_" + ID);
		}

		/** ===== Statistical Counters ===== **/
		if (isMonitored)
			net.hosts.get(segment.getSrcHostID()).transportAgent.flow.totalBufferTime += bufferTime;
		/** ================================ **/
		return net;
	}

	protected Network forwardToSwitch(Network net, int switchID, Segment segment) {
		float bufferTime = networkLinks.get(switchID).buffer.getBufferTime(net.getCurrentTime(),
				networkLinks.get(switchID).getTransmissionDelay(segment.getSize()));
		float nextTime = net.getCurrentTime() + bufferTime;
		if (nextTime > 0) {
			Event nextEvent = new DepartureFromSwitch(nextTime, this.ID, switchID, new Packet(segment, null));
			net.eventList.addEvent(nextEvent);
		} else {
			// Packet Drop happens here
			Main.error("SDNSwitch", "forwardToSwitch", "Packe Drop happened at switch_" + ID);
		}
		/** ===== Statistical Counters ===== **/
		if (isMonitored) {
			networkLinks.get(switchID).updateSegementArrivalToLinkCounters(segment.getFlowID(), net.getCurrentTime());
		}
		/** ================================ **/
		return net;
	}

	protected Network forwardToController(Network net, Segment segment) {
		float bufferTime = controlLink.buffer.getBufferTime(net.getCurrentTime(),
				controlLink.getTransmissionDelay(segment.getSize()));
		float nextTime = net.getCurrentTime() + bufferTime;
		Event nextEvent = new DepartureFromSwitch(nextTime, this.ID, controllerID, new Packet(segment, null));
		net.eventList.addEvent(nextEvent);

		return net;
	}

	protected int getNextSwitchID(int flowID) {
		return flowTable.get(flowID);
	}

	protected float getAccessLinkTotalDelay(int hostID, int segmentSize) {
		return accessLinks.get(hostID).getTotalDelay(segmentSize);
	}

	protected float getNetworkLinkTotalDelay(int flowID, int segmentSize) {
		return networkLinks.get(getNextSwitchID(flowID)).getTotalDelay(segmentSize);
	}

	protected boolean isConnectedToHost(int hostID) {
		if (accessLinks.get(hostID) != null) {
			return true;
		} else {
			return false;
		}
	}

	protected void deQueueFromAccessLinkBuffer(int dstHostID) {
		accessLinks.get(dstHostID).buffer.deQueue();
	}

	protected void deQueueFromNetworkLinkBuffer(int flowID) {
		networkLinks.get(getNextSwitchID(flowID)).buffer.deQueue();
	}

	/* ########## Public ################################# */
	public boolean isAccessSwitch() {
		if (accessLinks.isEmpty()) {
			return false;
		} else {
			return true;
		}
	}
	/* ########## Private ################################# */

}
