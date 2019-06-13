package system;

import java.util.HashMap;
import entities.*;

public class Statistics {

	public HashMap<Integer, Link> links; // <LinkID, Link>
	public HashMap<Integer, SDNSwitch> switches; // <SwitchID, SDNSwitch>
	public HashMap<Integer, Flow> flows; // <FlowID, Flow>

	public Statistics(Network net) {
		links = new HashMap<Integer, Link>();
		switches = new HashMap<Integer, SDNSwitch>();
		flows = new HashMap<Integer, Flow>();
		this.switches = net.switches;
		for (SDNSwitch sdnSwitch : net.switches.values()) {
			for (Link link : sdnSwitch.networkLinks.values()) {
				this.links.put(link.getID(), link);
			}
		}
		for (Host host : net.hosts.values()) {
			this.flows.put(host.transportAgent.flow.getID(), host.transportAgent.flow);
		}

	}

}
