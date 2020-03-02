package nrg.sdnsimulator.core.entity.network.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

import nrg.sdnsimulator.core.Network;
import nrg.sdnsimulator.core.entity.network.Agent;
import nrg.sdnsimulator.core.entity.network.Timer;
import nrg.sdnsimulator.core.entity.traffic.Flow;
import nrg.sdnsimulator.core.entity.traffic.Segment;
import nrg.sdnsimulator.core.event.TimeoutEvent;
import nrg.sdnsimulator.core.system.SimApp;
import nrg.sdnsimulator.core.utility.Debugger;
import nrg.sdnsimulator.core.utility.Keywords;
import nrg.sdnsimulator.core.utility.Mathematics;

public class Senderv2 extends Agent {

	private boolean validationReport = false;
	private boolean hasRecvdSYNACK;
	private boolean hasStartedSending;
	private boolean hasSentFIN;
	private HashMap<Integer, Timer> timerOfTimerID;
	private ArrayList<Integer> timeToNextCycleTimerIDs;
	private int timerIndex;
	private HashMap<Integer, CCParams> nextCCParamsOfTimerID;
	private CCParams ccParams;
	private int remainingSegments;
	private int mostRecentSequenceNum;
	private int lastSentSeqNum;
	private TreeMap<Integer, Boolean> negativeACKWaitingList; // <NACKSequenceNumber,
																// isRetransmission>
	private TreeMap<Integer, Boolean> seqNumbersToSend; // <SequenceNumber, isRetransmission>

	public Senderv2(Flow flow) {
		super(flow);

		timerOfTimerID = new HashMap<Integer, Timer>();
		timeToNextCycleTimerIDs = new ArrayList<Integer>();
		timerIndex = -1;
		nextCCParamsOfTimerID = new HashMap<Integer, CCParams>();
		ccParams = new CCParams(-1, -1, -1, -1);
		remainingSegments = flow.getSize();
		lastSentSeqNum = -1;
		mostRecentSequenceNum = -1;
		negativeACKWaitingList = new TreeMap<Integer, Boolean>();
		seqNumbersToSend = new TreeMap<Integer, Boolean>();
		validation = true;
		verification = true;
		hasRecvdSYNACK = false;
		hasSentFIN = false;
		hasStartedSending = false;

	}

	@Override
	public void sendFirst(Network net) {
		segmentsToSend.clear();
		segmentsToSend.add(genSYN());
	}

	@Override
	public void recvSegment(Network net, Segment segment) {
		// if (!hasSentFIN) {
		switch (segment.getType()) {
		case Keywords.Segments.Types.SYN:
			segmentsToSend.clear();
			segmentsToSend.add(segment);
			break;
		case Keywords.Segments.Types.CTRL:
			if (validationReport) {
				Debugger.debugToConsole("********* Flow: " + flow.getID() + ", recvd CTRL at Time: "
						+ net.getCurrentTime());
				Debugger.debugToConsole("     sCycle should start at time = "
						+ Mathematics.addFloat(net.getCurrentTime(), segment.getTimeToNextCycle()));
			}
			// Stop all running TimeToNextCycleTimers
			deactivateSendingCycleTimers();
			// Create the new TimetToNextCycleTimer
			if (!hasSentFIN) {
				timerIndex++;
				Timer timeToNextCycleTimer = new Timer(timerIndex,
						Keywords.Entities.Agents.TimerTypes.TimeToCycleTimer);
				timerOfTimerID.put(timeToNextCycleTimer.getId(), timeToNextCycleTimer);
				net.getEventList()
						.addEvent(new TimeoutEvent(
								Mathematics.addFloat(net.getCurrentTime(), segment.getTimeToNextCycle()),
								srcHostID, timeToNextCycleTimer.getId()));
				nextCCParamsOfTimerID.put(timeToNextCycleTimer.getId(), new CCParams(segment.getsWnd(),
						segment.getsInterSegmentDelay(), segment.getsInterval(), segment.getsInitialDelay()));
				timeToNextCycleTimerIDs.add(timeToNextCycleTimer.getId());
			}
			break;
		case Keywords.Segments.Types.SYNACK:
			if (validationReport) {
				Debugger.debugToConsole("********* SYNACK of Flow: " + flow.getID()
						+ ", arrived at Time: " + net.getCurrentTime());
			}
			/** ===== Statistical Counters ===== **/
			flow.getAckSeqNumArrivalTimes().put((float) segment.getSeqNum(), net.getCurrentTime());
			/** ================================ **/
			hasRecvdSYNACK = true;
			break;
		case Keywords.Segments.Types.ACK:
			/** ===== Statistical Counters ===== **/
			flow.getAckSeqNumArrivalTimes().put((float) segment.getSeqNum(), net.getCurrentTime());
			/** ================================ **/
			if (remainingSegments == 0 && negativeACKWaitingList.isEmpty()
					&& segment.getSeqNum() == lastSentSeqNum) {
				segmentsToSend.add(genFIN());
				for (int timerID : timerOfTimerID.keySet()) {
					timerOfTimerID.get(timerID).setActive(false);
				}
				/** ===== Statistical Counters ===== **/
				flow.setCompletionTime(net.getCurrentTime());
				flow.setFINSendingTime(net.getCurrentTime());
				/** ================================ **/
			}
			break;
		case Keywords.Segments.Types.NACK:
			negativeACKWaitingList.put(segment.getSeqNum(), true);
			break;
		case Keywords.Segments.Types.FINACK:
			break;
		default:
			SimApp.error("Senderv2", "recvSegment", "Invalid segmentType.");
			Debugger.stopFlag();
			break;
		}
		// }
	}

	@Override
	public void timeout(Network net, int timerID) {
		Timer currentTimer = timerOfTimerID.get(timerID);
		timerOfTimerID.remove(timerID);
		if (currentTimer.isActive()) {
			switch (currentTimer.getType()) {
			case Keywords.Entities.Agents.TimerTypes.TimeToCycleTimer:
				// Clear seqNum to send
				seqNumbersToSend.clear();
				// Deactivate all running timers
				deactivateAllTimers();
				// Update ccParams
				ccParams = nextCCParamsOfTimerID.get(timerID);
				nextCCParamsOfTimerID.remove(timerID);
				if (hasRecvdSYNACK) {
					startSendingCycle(net);
				} else {
					Debugger.error("Senderv2", "timeOut",
							"Cycle starts before SYNACK arrival. Time = " + net.getCurrentTime()
									+ ", flowID = " + flow.getID());
					// Debugger.stopFlag();
				}
				break;
			case Keywords.Entities.Agents.TimerTypes.InitialDelayTimer:
				startSendingInterval(net);
				break;
			case Keywords.Entities.Agents.TimerTypes.IntervalTimer:
				startSendingInterval(net);
				break;
			case Keywords.Entities.Agents.TimerTypes.InterSegmentDelayTimer:
				sendSingleSegment(net);
				break;
			default:
				break;
			}
		}
	}

	private void startSendingCycle(Network net) {
		if (validationReport) {
			Debugger.debugToConsole("********* sCycle of Flow: " + flow.getID()
					+ ", starts at Time: " + net.getCurrentTime());
		}
		if (ccParams.getsInitialDelay() == 0) {
			startSendingInterval(net);
		} else if (ccParams.getsInitialDelay() > 0) {
			timerIndex++;
			Timer initialDelayTimer = new Timer(timerIndex,
					Keywords.Entities.Agents.TimerTypes.InitialDelayTimer);
			timerOfTimerID.put(initialDelayTimer.getId(), initialDelayTimer);
			net.getEventList()
					.addEvent(new TimeoutEvent(
							Mathematics.addFloat(net.getCurrentTime(), ccParams.getsInitialDelay()),
							srcHostID, initialDelayTimer.getId()));
		} else {
			Debugger.error("Senderv2", "startSendingCycle",
					"Invalid sInitialDelay (= " + ccParams.getsInitialDelay() + ")");
			Debugger.stopFlag();
		}
	}

	private void startSendingInterval(Network net) {
		if (!hasStartedSending) {
			flow.updateDataSendingStartTime(net.getCurrentTime());
			hasStartedSending = true;
		}
		if (!hasSentFIN) {
			for (int seqNumCount = 0; seqNumCount < ccParams.getsWnd(); seqNumCount++) {
				if (!negativeACKWaitingList.isEmpty()) {
					int sequenceNumber = negativeACKWaitingList.firstKey();
					negativeACKWaitingList.remove(sequenceNumber);
					seqNumbersToSend.put(sequenceNumber, true);
				}
			}
			int upperLimit = (int) Mathematics.minDouble((double) ccParams.getsWnd(),
					(double) remainingSegments);
			mostRecentSequenceNum = lastSentSeqNum;
			for (int seqNumCount = 0; seqNumCount < upperLimit; seqNumCount++) {
				mostRecentSequenceNum++;
				seqNumbersToSend.put(mostRecentSequenceNum, false);
			}
			if (seqNumbersToSend.size() > 0) {
				sendSingleSegment(net);
			}
			timerIndex++;
			Timer sIntervalTimer = new Timer(timerIndex,
					Keywords.Entities.Agents.TimerTypes.IntervalTimer);
			timerOfTimerID.put(sIntervalTimer.getId(), sIntervalTimer);
			net.getEventList()
					.addEvent(new TimeoutEvent(
							Mathematics.addFloat(net.getCurrentTime(), ccParams.getsInterval()),
							srcHostID, sIntervalTimer.getId()));
		}
	}

	private void sendSingleSegment(Network net) {
		if (!seqNumbersToSend.isEmpty()) {
			int sequenceNumber = seqNumbersToSend.firstKey();
			lastSentSeqNum = sequenceNumber; // TODO note for the retransmissions
			segmentsToSend
					.add(genDATASegment(sequenceNumber, seqNumbersToSend.get(sequenceNumber)));
			seqNumbersToSend.remove(sequenceNumber);
			if (!seqNumbersToSend.isEmpty()) {
				timerIndex++;
				Timer interSegmentDelayTimer = new Timer(timerIndex,
						Keywords.Entities.Agents.TimerTypes.InterSegmentDelayTimer);
				timerOfTimerID.put(interSegmentDelayTimer.getId(), interSegmentDelayTimer);
				net.getEventList()
						.addEvent(new TimeoutEvent(
								Mathematics.addFloat(net.getCurrentTime(),
										ccParams.getsInterSegmentDelay()),
								srcHostID, interSegmentDelayTimer.getId()));
			}
		}
	}

	private Segment genSYN() {
		Segment segment = new Segment(flow.getID(), Keywords.Segments.Types.SYN,
				Keywords.Segments.SpecialSequenceNumbers.SYNSeqNum,
				Keywords.Segments.Sizes.SYNSegSize, srcHostID, dstHostID);
		lastSentSeqNum = 0;
		mostRecentSequenceNum = 0;
		return segment;
	}

	private Segment genFIN() {
		lastSentSeqNum++;
		mostRecentSequenceNum++;
		Segment segment = new Segment(flow.getID(), Keywords.Segments.Types.UncontrolledFIN,
				lastSentSeqNum, Keywords.Segments.Sizes.FINSegSize, srcHostID, dstHostID);
		hasSentFIN = true;
		return segment;
	}

	private Segment genDATASegment(int seqNumber, boolean isRetransmission) {
		if (remainingSegments > 0) {
			Segment segment = new Segment(this.flow.getID(), Keywords.Segments.Types.DATA,
					seqNumber, Keywords.Segments.Sizes.DataSegSize, srcHostID, dstHostID);
			if (!isRetransmission) {
				remainingSegments--;
			}
			return segment;
		} else if (remainingSegments == 0) {
			Debugger.error("Senderv2", "genDATASegment",
					"CASE 0: Upper bound for sending window and remaining segments is wrong.");
			Debugger.stopFlag();
			return null;
		} else {
			Debugger.error("Senderv2", "genDATASegment",
					"CASE Negative: Upper bound for sending window and remaining segments is wrong.");
			Debugger.stopFlag();
			return null;
		}

	}

	private void deactivateAllTimers() {
		for (int timerID : timerOfTimerID.keySet()) {
			timerOfTimerID.get(timerID).setActive(false);
		}
		timeToNextCycleTimerIDs.clear();

	}

	private void deactivateSendingCycleTimers() {
		for (int timerID : timeToNextCycleTimerIDs) {
			if (timerOfTimerID.containsKey(timerID)) {
				timerOfTimerID.get(timerID).setActive(false);
			}
		}
		timeToNextCycleTimerIDs.clear();
	}

	private void deactivateTimer(int timerID) {
		timerOfTimerID.get(timerID).setActive(false);
	}

	public boolean isValidationReport() {
		return validationReport;
	}

	public void setValidationReport(boolean validationReport) {
		this.validationReport = validationReport;
	}

	public boolean isHasRecvdSYNACK() {
		return hasRecvdSYNACK;
	}

	public void setHasRecvdSYNACK(boolean hasRecvdSYNACK) {
		this.hasRecvdSYNACK = hasRecvdSYNACK;
	}

	public boolean isHasStartedSending() {
		return hasStartedSending;
	}

	public void setHasStartedSending(boolean hasStartedSending) {
		this.hasStartedSending = hasStartedSending;
	}

	public boolean isHasSentFIN() {
		return hasSentFIN;
	}

	public void setHasSentFIN(boolean hasSentFIN) {
		this.hasSentFIN = hasSentFIN;
	}

	public HashMap<Integer, Timer> getTimerOfTimerID() {
		return timerOfTimerID;
	}

	public void setTimerOfTimerID(HashMap<Integer, Timer> timerOfTimerID) {
		this.timerOfTimerID = timerOfTimerID;
	}

	public ArrayList<Integer> getTimeToNextCycleTimerIDs() {
		return timeToNextCycleTimerIDs;
	}

	public void setTimeToNextCycleTimerIDs(ArrayList<Integer> timeToNextCycleTimerIDs) {
		this.timeToNextCycleTimerIDs = timeToNextCycleTimerIDs;
	}

	public int getTimerIndex() {
		return timerIndex;
	}

	public void setTimerIndex(int timerIndex) {
		this.timerIndex = timerIndex;
	}

	public HashMap<Integer, CCParams> getNextCCParamsOfTimerID() {
		return nextCCParamsOfTimerID;
	}

	public void setNextCCParamsOfTimerID(HashMap<Integer, CCParams> nextCCParamsOfTimerID) {
		this.nextCCParamsOfTimerID = nextCCParamsOfTimerID;
	}

	public CCParams getCcParams() {
		return ccParams;
	}

	public void setCcParams(CCParams ccParams) {
		this.ccParams = ccParams;
	}

	public int getRemainingSegments() {
		return remainingSegments;
	}

	public void setRemainingSegments(int remainingSegments) {
		this.remainingSegments = remainingSegments;
	}

	public int getMostRecentSequenceNum() {
		return mostRecentSequenceNum;
	}

	public void setMostRecentSequenceNum(int mostRecentSequenceNum) {
		this.mostRecentSequenceNum = mostRecentSequenceNum;
	}

	public int getLastSentSeqNum() {
		return lastSentSeqNum;
	}

	public void setLastSentSeqNum(int lastSentSeqNum) {
		this.lastSentSeqNum = lastSentSeqNum;
	}

	public TreeMap<Integer, Boolean> getNegativeACKWaitingList() {
		return negativeACKWaitingList;
	}

	public void setNegativeACKWaitingList(TreeMap<Integer, Boolean> negativeACKWaitingList) {
		this.negativeACKWaitingList = negativeACKWaitingList;
	}

	public TreeMap<Integer, Boolean> getSeqNumbersToSend() {
		return seqNumbersToSend;
	}

	public void setSeqNumbersToSend(TreeMap<Integer, Boolean> seqNumbersToSend) {
		this.seqNumbersToSend = seqNumbersToSend;
	}

}