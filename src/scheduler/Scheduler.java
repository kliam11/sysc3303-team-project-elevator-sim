package scheduler;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import other.ElevatorMessage;
import other.ElevatorState;
import other.FloorMessage;
import other.Message;
import other.MessageListener;
import other.MessageType;
import other.Messenger;
import other.Ports;
import other.SchedulerState;
import other.SchedulerState.Transition;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Iterator;

import gui.GUI;
import javafx.application.Platform;

/**
 * This class represents a Scheduler. Scheduler will try to schedule 
 * if there are pending messages.
 *
 */
public class Scheduler implements Runnable, MessageListener {
	private static GUI gui; 

	private SchedulerStateMachine stateMachine;
	private Messenger messenger;
	private Queue<Message> messages;
	private Map<Integer, ElevatorModel> elevatorModel;
	
	/**
	 * Constructor for scheduler
	 */
	public Scheduler() {
		messages = new LinkedList<Message>();
		elevatorModel = new HashMap<Integer, ElevatorModel>();
		
		messenger = Messenger.getMessenger();
		stateMachine = new SchedulerStateMachine();
		
		gui = new GUI(); 
	}
	
	/**
	 * Tries to schedule based on the requests. Will wait if there
	 * is no requests.
	 */
	public void run() {
		messenger.receive(Ports.SCHEDULER_PORT, this);
		System.out.println("Scheduler listening on " + Ports.SCHEDULER_PORT);
			
		while(true) {
			synchronized (messages) {
				while(stateMachine.getCurrentState() == SchedulerState.IDLE) {
					System.out.println("Scheduler: Current state is " + stateMachine.getCurrentState());
					try {
						messages.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					System.out.println("Scheduler: Current state is " + stateMachine.getCurrentState());
				}
				
				System.out.println("Scheduler: Scheduling...");
				schedule();
				System.out.println("Scheduler: Done scheduling");
				stateMachine.onNext(SchedulerState.Transition.FINISHED_SCHEDULING);
			}
			
			
		}
	}
	
	/**
	 * Action performed when a message has been received.
	 */
	@Override
	public void onMessageReceived(Message message) {
		System.out.println("Scheduler: Received message: " + message.getType() + " " + message.getBody());
		Thread messageWriter = new Thread(new Runnable() {
			public void run() {
				synchronized (messages) {
					while(stateMachine.getCurrentState() == SchedulerState.BUSY) {
						try {
							messages.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					
					
					messages.add(message);
					stateMachine.onNext(Transition.RECEIVED_MESSAGE);
					messages.notifyAll();
				}
			}
		});
		
		messageWriter.start();
	}
	
	/**
	 * Schedule based on the messages.
	 */
	private void schedule() {
		// Handle scheduling
		Iterator<Message> msgIter = messages.iterator();
		while(msgIter.hasNext()) {
			Message msg = msgIter.next();
			
			if(msg.getType() == MessageType.ELEVATOR) {
				// The message might have three meaning
				ElevatorMessage em = new ElevatorMessage(msg);
				ElevatorModel elevator = null;
				if(!elevatorModel.containsKey(em.getElevatorNum())) {
					elevator = new ElevatorModel(em.getElevatorNum(), em.getState(), em.getCurrentFloor());
					elevatorModel.put(em.getElevatorNum(), elevator);
				} else {
					// Update the corresponding elevator state
					elevator = elevatorModel.get(em.getElevatorNum());
					elevator.setCurrentFloor(em.getCurrentFloor());
					elevator.setState(em.getState());
					
					// Update GUI 
					
					//messenger.send(msg, Ports.GUI_PORT, null); 
					
					
				} 
				
				// Give the elevator the queued messages
				if(elevator.getState() == ElevatorState.IDLE) {
					/* 
					 * If the elevator went idle after moving up then it's time to pick up all the messengers that wants to go down. Else if
					 * the elevator went idle after moving down then it's time to pick up all the messengers that wants to go up.
					 * 
					 */
					Queue<FloorMessage> queue = elevator.getPrevState() == ElevatorState.MOVINGUP ? elevator.getDownQueue() : elevator.getUpQueue();
					for(int i = 0; i < queue.size(); i++) {
						try {
							messenger.send(queue.poll().toMessage(), Ports.ELEVATOR_PORT, InetAddress.getLocalHost());
						} catch (UnknownHostException e) {
							e.printStackTrace();
						}
					}
				}
				
				// Tell floor to open door
				if(elevator.getState() == ElevatorState.DOOROPEN) {
					try {
						messenger.send(em.toMessage(), Ports.FLOOR_PORT, InetAddress.getLocalHost());
					} catch (UnknownHostException e) {
						e.printStackTrace();
					}
				} else if (elevator.getState() == ElevatorState.DOORCLOSE) { // Tell floor to close door
					try {
						messenger.send(em.toMessage(), Ports.FLOOR_PORT, InetAddress.getLocalHost());
					} catch (UnknownHostException e) {
						e.printStackTrace();
					}
				}
				
			} else if(msg.getType() == MessageType.FLOOR) {
				FloorMessage fm = new FloorMessage(msg);
								
				if (!elevatorModel.containsKey(fm.getEleNum())) {
					try {
						messenger.send(fm.toMessage(), Ports.ELEVATOR_PORT, InetAddress.getLocalHost());
					} catch (UnknownHostException e) {
						e.printStackTrace();
					}
					continue;
				}
				
				// Retrieve the corresponding the elevator model
				ElevatorModel elevator = elevatorModel.get(fm.getEleNum());
				
				if(fm.getDirection().equalsIgnoreCase("up")) {
					// Elevator is on the way up and is able to pick up this floor
					if(elevator.getState() == ElevatorState.MOVINGUP && elevator.getCurrentFloor() < fm.getFloorNum()) {
						try {
							messenger.send(fm.toMessage(), Ports.ELEVATOR_PORT, InetAddress.getLocalHost());
						} catch (UnknownHostException e) {
							e.printStackTrace();
						}
					} else {
						elevator.getUpQueue().add(fm);
					}
				} else if (fm.getDirection().equalsIgnoreCase("down")) {
					// Elevator is on the way down and is able to pick up this floor
					if(elevator.getState() == ElevatorState.MOVINGDOWN && elevator.getCurrentFloor() > fm.getFloorNum()) {
						try {
							messenger.send(fm.toMessage(), Ports.ELEVATOR_PORT, InetAddress.getLocalHost());
						} catch (UnknownHostException e) {
							e.printStackTrace();
						}
					} else {
						elevator.getDownQueue().add(fm);
					}
				} else if (fm.getDirection().equalsIgnoreCase("FINISHED_LOAD")) {
					try {
						messenger.send(fm.toMessage(), Ports.ELEVATOR_PORT, InetAddress.getLocalHost());
					} catch (UnknownHostException e) {
						e.printStackTrace();
					}
				}
			}	
			msgIter.remove();
		}
	}
	
	/**
	 * @return the messages queue
	 */
	public Queue<Message> getMessages() {
		return messages;
	}
	
	public GUI getGUI() {
		return gui; 
	}
	
	public static void main(String[] args) {
		Thread scheduler = new Thread(new Scheduler());
		
		scheduler.start();
		gui.main(args);
	}
}