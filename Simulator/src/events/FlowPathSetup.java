package events;

import system.Event;
import utilities.*;
import system.Network;
import system.Simulator;

public class FlowPathSetup extends Event {

	private int flowID;
	private int neighborID;

	public FlowPathSetup(double startTime, int switchID, int flowID, int neighborID) {
		super(Keywords.FlowPathSetup, startTime, switchID, null);
		this.flowID = flowID;
		this.neighborID = neighborID;
	}

	public Network execute(Network net) {
		// Debugger.event(this.type, this.time, this.nodeID, this.segment, null);
		net.updateTime(time);
		net.switches.get(this.nodeID).addFlowTableEntry(flowID, neighborID);
		net.switches.get(neighborID).addFlowTableEntry(Simulator.ACKStreamID(flowID), nodeID);

		return net;
	}

}
