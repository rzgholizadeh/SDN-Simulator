package network;

public class Buffer {
	private int capacity;
	private String policy;
	public int occupancy;

	/* Shows the waiting time for the upcoming packet to be sent */
	/* Must be updated each time a packet is Enqueued */
	private double wait_time;

	public Buffer(int cap, String policy) {
		this.capacity = cap;
		this.policy = policy;
		occupancy = 0;
		// TODO Auto-generated constructor stub
	}

	public String enQueue() {
		return null;
	}

	/* Called in Class::Event */
	/* Return true if the buffer is full */
	public boolean isFull() {
		if (occupancy < capacity) {
			return false;
		} else if (occupancy == capacity) {
			return true;
		}
		System.out.println("Error Class::Buffer--Invalid buffer occupancy(" + occupancy + ").");
		return false;
	}
}
