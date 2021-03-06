package  logicAnalysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import   common.DualHashMap;
import   common.IndexObjMap;
import   common.IntArrayObj;
import   common.MddTable;
import   common.SetIntTuple;
import   expression.VarNode;
import   gui.Console;
import   lpn.LPN;
import   lpn.LPNTran;
import   lpn.LpnTranList;
import   lpn.VarSet;
import   main.Options;
import   main.Options.CompMinDef;
import   stategraph.State;
import   stategraph.StateError;
import   stategraph.StateGraph;
import   stategraph.StateTran;
import   util.Pair;
import   util.RuntimeMonitor;

/**
 * Static class used to contain the functions necessary for compositional analysis.
 */
public class CompositionalAnalysis {
	private static final List<List<Constraint>> emptyConstraintList = new LinkedList<List<Constraint>>();
	private static final List<Set<Constraint>> emptyConstraintSet = new LinkedList<Set<Constraint>>();
	private static final List<List<State>> emptyStateList = new LinkedList<List<State>>();
	private static final List<List<Pair<int[], int[]>>> emptyPairList = new LinkedList<List<Pair<int[], int[]>>>();
	private static final Pair<int[], int[]> emptyPair = new Pair<int[], int[]>(null, null);
	
	//
	private static List<Pair<String, Integer>> globalVarList = null;
	
	// holds each state graph's set of constraints that were generated at least 2 iterations ago
	private static List<List<Constraint>> oldConstraintList = emptyConstraintList;
	
	// holds each state graph's set of constraints that were generated in the last iteration
	private static List<List<Constraint>> newConstraintList = emptyConstraintList;
	
	// holds each state graph's set of constraints that were generated in the current iteration
	private static List<List<Constraint>> frontierConstraintList = emptyConstraintList;
	
	// holds each state graph's complete set of constraints
	private static List<Set<Constraint>> constraintSetList = emptyConstraintSet;

	// hold each state graph's set of states that were generated at least 2 iterations ago
	private static List<List<State>> stateSetList = emptyStateList;
	
	// holds each state graph's set of states that were generated in the last iteration
	private static List<List<State>> frontierStateSetList = emptyStateList;
	
	// holds each state graph's set of states that were generated in the current iteration
	private static List<List<State>> entryStateSetList = emptyStateList;
	
	// holds each state graph's set of interfacing indices with all other state graph
	private static List<List<Pair<int[], int[]>>> interfacePairList = emptyPairList;
	
	private StateGraph[] designUnitSet;
	
	private long globalPeakMemUsed = 0;
	private long globalPeakMemAlloc = 0;
	private long globalTimeElapsed = 0;
	private int globalLargestSG = 0;
	
	private long localPeakMemUsed = 0;
	private long localTimeElapsed = 0;
	private long localPeakMemAlloc = 0;
	private int localLargestSG = 0;
	
	/**
	 * Constructor
	 */
	public CompositionalAnalysis(StateGraph[] designUnitSet){
		this.designUnitSet = designUnitSet;
	}
	
	public static void setGlobalVarList(List<Pair<String, Integer>> gVarList){
		globalVarList = gVarList;
	}
	
	/**
	 * Checks if the two specified state graphs are compatible for parallel composition.
	 * They are compatible if they share a common interface variable
	 * @return TRUE if compatible, otherwise FALSE
	 */
	private static boolean compatibleForComposition(CompositeStateGraph sg1, CompositeStateGraph sg2){
		// module state graphs composing sg1
		StateGraph[] moduleArray1 = sg1.getStateGraphArray();
		
		// module state graphs composing sg2
		StateGraph[] moduleArray2 = sg2.getStateGraphArray();
		
		/*
		 * Check if sg1 modifies sg2.  Also check if they share a global or input variable.
		 */
		for(StateGraph m1 : moduleArray1){
			// check for common global variables
			for(String global : m1.getLpn().getGlobals()){
				for(StateGraph m2 : moduleArray2){
					VarSet globals = m2.getLpn().getGlobals();
					if(globals.contains(global)){
						return true;
					}
				}
			}
		}
		
		return false;
	}
	
	/**
	 * Performs parallel composition on the specified composite state graphs sg1 and sg2.
	 * Returns the resulting composite state graph sg1||sg2 if they are compatible.
	 * Parallel composition can only be performed if sg1 modifies an input of sg2 or vice versa.
	 * @param sg1 - Composite state graph
	 * @param sg2 - Composite state graph
	 * @return Resulting composite state graph sg1||sg2 if compatible for composition, otherwise NULL
	 */
	private static CompositeStateGraph compose(CompositeStateGraph sg1, CompositeStateGraph sg2){
		long start = System.currentTimeMillis(); 
		
		if(sg1 == null || sg2 == null){
			return null;
		}
		
		/*
		 * Checks if the two state graphs have a common variable to synchronize transitions on
		 */
		boolean compatible = compatibleForComposition(sg1, sg2);
		if(!compatible){
			if(Options.getVerbosity() > 20){
				System.out.println("   state graphs " + sg1.getLabel() + " and " + sg2.getLabel() + " cannot be composed\n");
			}
			
			return null;
		}
		
		// module state graphs composing sg1
		StateGraph[] moduleArray1 = sg1.getStateGraphArray();
		
		// module state graphs composing sg2
		StateGraph[] moduleArray2 = sg2.getStateGraphArray();
		
		/*
		 * Check if all the modules in each state graph are already contained in the other
		 */
		boolean contained = false;
		for(StateGraph m1 : moduleArray1){
			contained = false;

			for(StateGraph m2 : moduleArray2){
				if(m1 == m2){
					contained = true;
					break;
				}
			}
			
			if(contained == false){
				break;
			}
		}
		
		if(contained){
			if(Options.getVerbosity() > 20){
				System.out.println("   " + sg1.getLabel() + " is already contained by " + sg2.getLabel());
			}
			
			return sg2;
		}
		
		for(StateGraph m2 : moduleArray2){
			contained = false;
			
			for(StateGraph m1 : moduleArray1){
				if(m1 == m2){
					contained = true;
					break;
				}
			}
			
			if(contained == false){
				break;
			}
		}
		
		if(contained){
			if(Options.getVerbosity() > 20){
				System.out.println("   " + sg2.getLabel() + " is already contained by " + sg1.getLabel());
			}
			
			return sg1;
		}

		/*
		 * Find the indices of the module that are not contained in sg1
		 */
		List<Integer> indexList = new LinkedList<Integer>();
		for(int i = 0; i < moduleArray2.length; i++){
			StateGraph m2 = moduleArray2[i];
			
			boolean eq = false;
			for(StateGraph m1 : moduleArray1){
				if(m2 == m1){
					eq = true;
					break;
				}
			}
			
			if(!eq){
				indexList.add(i);
			}
		}
		
		/*
		 * Convert the linked list into an array for faster access
		 */
		int[] indexArray = new int[indexList.size()];
		for(int i = 0; i < indexList.size(); i++){
			indexArray[i] = indexList.get(i);
		}
		
		// size of the new composite state graph
		int size = sg1.getSize() + indexArray.length;
		
		// size of the first composite state graph
		int sg1Size = sg1.getSize();
		
		/*
		 * Create initial composite state
		 */
		int[] initStates = new int[size];
		for(int i = 0; i < sg1Size; i++){
			initStates[i] = moduleArray1[i].getInitialState().getIndex();
		}
		
		int idx = 0;
		for(int i = sg1Size; i < size; i++){
			initStates[i] = moduleArray2[indexArray[idx++]].getInitialState().getIndex();
		}
		
		CompositeState initCompositeState = new CompositeState(initStates);
		
		StateError ec1 = sg1.getInitState().getErrorCode();
		if(ec1 != StateError.NONE){
			initCompositeState.setErrorCode(ec1);
		}
		
		StateError ec2 = sg2.getInitState().getErrorCode();
		if(ec2 != StateError.NONE){
			initCompositeState.setErrorCode(ec2);
		}

		/*
		 * Find the set of LPNTrans that modify an input to the other composite state graph.
		 * Transitions on these LPNTrans should be synchronized.
		 */
		HashSet<LPNTran> synchronousTrans = new HashSet<LPNTran>();
		for(StateGraph g1 : moduleArray1){
			LPN lpn1 = g1.getLpn();

			for(StateGraph g2 : moduleArray2){
				LPN lpn2 = g2.getLpn();
				
				if(lpn1 == lpn2){
					synchronousTrans.addAll(lpn1.getTransitions());
					continue;
				}
				
				HashSet<LPNTran> inputTrans = new HashSet<LPNTran>();
				inputTrans.addAll(lpn1.getInputTranSet());
				inputTrans.retainAll(lpn2.getOutputTranSet());
				
				HashSet<LPNTran> outputTrans = new HashSet<LPNTran>();
				outputTrans.addAll(lpn2.getInputTranSet());
				outputTrans.retainAll(lpn1.getOutputTranSet());
				
				HashSet<LPNTran> sharedTrans = new HashSet<LPNTran>();
				sharedTrans.addAll(lpn2.getInputTranSet());
				sharedTrans.retainAll(lpn1.getInputTranSet());
				
				synchronousTrans.addAll(inputTrans);
				synchronousTrans.addAll(outputTrans);
				synchronousTrans.addAll(sharedTrans);
			}
		}
		
		/*
		 * Create the new composite state graph
		 */
		StateGraph[] sgArray = new StateGraph[size];
		List<LPN> lpnList = new ArrayList<LPN>(size);
		for(int i = 0; i < sg1Size; i++){
			sgArray[i] = moduleArray1[i];
			lpnList.add(moduleArray1[i].getLpn());
		}
		
		idx = 0;
		for(int i = sg1Size; i < size; i++){
			int j = indexArray[idx++];
			sgArray[i] = moduleArray2[j];
			lpnList.add(moduleArray2[j].getLpn());
		}
		
		CompositeStateGraph compositeSG = new CompositeStateGraph(initCompositeState, sgArray);
		if(initCompositeState.getErrorCode() != StateError.NONE){
			return compositeSG;
		}
		
		// create CompositeState stack
		Stack<CompositeState> newStateStack = new Stack<CompositeState>();
		Stack<CompositeState> subStateStack1 = new Stack<CompositeState>();
		Stack<CompositeState> subStateStack2 = new Stack<CompositeState>();
		
		// initialize with initial CompositeState
		newStateStack.push(initCompositeState);
		subStateStack1.push(sg1.getInitState());
		subStateStack2.push(sg2.getInitState());
		
		List<CompositeStateTran> intersectingTrans1 = new ArrayList<CompositeStateTran>();
		List<CompositeStateTran> intersectingTrans2 = new ArrayList<CompositeStateTran>();
		List<CompositeStateTran> independentTrans1 = new ArrayList<CompositeStateTran>();
		List<CompositeStateTran> independentTrans2 = new ArrayList<CompositeStateTran>();
		
		long peakUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		long peakTotal = Runtime.getRuntime().totalMemory();
		
		CompositeState returnState = null;
		while(!newStateStack.isEmpty()){
			boolean afr = false;
			CompositeState currentState = newStateStack.pop();
			CompositeState subState1 = subStateStack1.pop();
			CompositeState subState2 = subStateStack2.pop();
			
			int[] subState1Tuple = subState1.getStateTuple();
			int[] subState2Tuple = subState2.getStateTuple();
			
			List<CompositeStateTran> stateTrans1 = subState1.getOutgoingStateTranList();
			List<CompositeStateTran> stateTrans2 = subState2.getOutgoingStateTranList();
			
			// clear reused lists
			intersectingTrans1.clear();
			intersectingTrans2.clear();
			independentTrans1.clear();
			independentTrans2.clear();
			
			//TODO: cache a state's independent and synch lists
			//TODO: traverse in a manner that can reuse previous data
			
			/*
			 * Determine whether each enabled LPNTran should be synchronized or fired independently.
			 * If an LPNTran should be synchronized make sure it is enabled in both states.
			 */
			for(int i=0; i<stateTrans1.size(); i++){
				CompositeStateTran stateTran1 = stateTrans1.get(i);
				LPNTran lpnTran = stateTran1.getLPNTran();
				
				if(!stateTran1.visible()){
					independentTrans1.add(stateTran1);
				}
				else{
					if(synchronousTrans.contains(lpnTran)){
						for(int j=0; j<stateTrans2.size(); j++){
							CompositeStateTran stateTran2 = stateTrans2.get(j);
							
							LPNTran lpnTran2  = stateTran2.getLPNTran();
							if(lpnTran == lpnTran2){
								intersectingTrans1.add(stateTran1);
								intersectingTrans2.add(stateTran2);
							}
						}
					}
					else{
						independentTrans1.add(stateTran1);
					}
				}
			}
			
			for(int i=0; i<stateTrans2.size(); i++){
				CompositeStateTran stateTran2 = stateTrans2.get(i);
				LPNTran lpnTran = stateTran2.getLPNTran();
				
				if(!stateTran2.visible()){
					independentTrans2.add(stateTran2);
				}
				else{
					if(!synchronousTrans.contains(lpnTran)){
						independentTrans2.add(stateTran2);
					}
				}
			}
			
			/*
			 * Fire independent transitions from state subState1
			 */
			for(int i=0; i<independentTrans1.size(); i++){
				CompositeStateTran stateTran = independentTrans1.get(i);
				LPNTran lpnTran = stateTran.getLPNTran();
				CompositeState nextSubState = stateTran.getNextState();
				int[] nextStateTuple = nextSubState.getStateTuple();
				int[] newStateTuple = new int[size];
				
				for(int j = 0; j < sg1Size; j++){
					newStateTuple[j] = nextStateTuple[j];
				}
				
				int index = 0;
				for(int j = sg1Size; j < size; j++){
					newStateTuple[j] = subState2Tuple[indexArray[index++]];
				}
				
				CompositeState nextCompositeState = new CompositeState(newStateTuple);
				returnState = compositeSG.addState(nextCompositeState);
				
				if(returnState == nextCompositeState){
					StateError errorCode1 = nextSubState.getErrorCode();
					StateError errorCode2 = subState2.getErrorCode();
					
					if(errorCode1 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode1);
					}
					else if(errorCode2 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode2);
					}
					
					if(nextCompositeState.getErrorCode() == StateError.NONE){
						newStateStack.push(nextCompositeState);
						subStateStack1.push(nextSubState);
						subStateStack2.push(subState2);
					}
				}
				else{
					nextCompositeState = returnState;
				}
				
				CompositeStateTran newStateTran = compositeSG.addStateTran(currentState, nextCompositeState, lpnTran);
				if(stateTran.visible()){
					newStateTran.setVisibility();
				}
				
				/*
				 * If the next state is an error state perform partial AFR.
				 * Set the current state as the error state, remove the current state's
				 * outgoing state transitions, remove new next states from the stack, 
				 * and remove unreachable states.
				 */
				if(Options.getAutoFailureFlag() && nextCompositeState.getErrorCode() != StateError.NONE
						&& lpnList.contains(lpnTran.getLpn())){
					afr = true;
					currentState.setErrorCode(nextCompositeState.getErrorCode());
					
					for(CompositeStateTran stTran : currentState.getOutgoingStateTranList().
							toArray(new CompositeStateTran[currentState.numOutgoingTrans()])){
						CompositeState nextState = stTran.getNextState();
						
						// check if it is a newly generated next state
						if(nextState.numIncomingTrans() == 1 && nextState.getErrorCode() == StateError.NONE){							
							newStateStack.pop();
							subStateStack1.pop();
							subStateStack2.pop();
						}
						
						compositeSG.removeStateTran(stTran);
					}
					
					CompositionalMinimization.removeAllUnreachableStates(compositeSG);
					break;
				}
			}
			
			if(afr){				
				if(currentState.getErrorCode() == StateError.NONE && currentState.numOutgoingTrans() == 0){
					currentState.setErrorCode(StateError.DEADLOCK);
				}
				
				continue;
			}
			
			/*
			 * Fire independent transitions from substate2
			 */
			for(int n=0; n<independentTrans2.size(); n++){
				CompositeStateTran stateTran = independentTrans2.get(n);
				LPNTran lpnTran = stateTran.getLPNTran();
				CompositeState nextSubState = stateTran.getNextState();
				int[] nextStateTuple = nextSubState.getStateTuple();
				int[] newStateTuple = new int[size];
				
				for(int i = 0; i < sg1Size; i++){
					newStateTuple[i] = subState1Tuple[i];
				}
				
				int index = 0;
				for(int i = sg1Size; i < size; i++){
					newStateTuple[i] = nextStateTuple[indexArray[index++]];
				}
				
				CompositeState nextCompositeState = new CompositeState(newStateTuple);
				returnState = compositeSG.addState(nextCompositeState);
				if(returnState == nextCompositeState){
					StateError errorCode1 = subState1.getErrorCode();
					StateError errorCode2 = nextSubState.getErrorCode();
					if(errorCode1 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode1);
					}
					else if(errorCode2 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode2);
					}
					
					if(nextCompositeState.getErrorCode() == StateError.NONE){
						newStateStack.push(nextCompositeState);
						subStateStack1.push(subState1);
						subStateStack2.push(nextSubState);
					}
				}
				else{
					nextCompositeState = returnState;
				}
				
				CompositeStateTran newStateTran = compositeSG.addStateTran(currentState, nextCompositeState, lpnTran);
				if(stateTran.visible()){
					newStateTran.setVisibility();
				}
				
				/*
				 * If the next state is an error state perform partial AFR.
				 * Set the current state as the error state, remove the current state's
				 * outgoing state transitions, remove new next states from the stack, 
				 * and remove unreachable states.
				 */
				if(Options.getAutoFailureFlag() && nextCompositeState.getErrorCode() != StateError.NONE
						&& lpnList.contains(lpnTran.getLpn())){
					afr = true;
					currentState.setErrorCode(nextCompositeState.getErrorCode());

					for(CompositeStateTran stTran : currentState.getOutgoingStateTranList().
							toArray(new CompositeStateTran[currentState.numOutgoingTrans()])){
						CompositeState nextState = stTran.getNextState();
						
						// check if it is a newly generated next state
						if(nextState.numIncomingTrans() == 1 && nextState.getErrorCode() == StateError.NONE){
							newStateStack.pop();
							subStateStack1.pop();
							subStateStack2.pop();
						}
						
						compositeSG.removeStateTran(stTran);
					}
					
					CompositionalMinimization.removeAllUnreachableStates(compositeSG);
					break;
				}
			}
			
			if(afr){
				if(currentState.getErrorCode() == StateError.NONE && currentState.numOutgoingTrans() == 0){
					currentState.setErrorCode(StateError.DEADLOCK);
				}
				
				continue;
			}
			
			/*
			 * fire synchronized state transitions 
			 */
			for(int n=0; n<intersectingTrans1.size(); n++){
				CompositeStateTran stateTran1 = intersectingTrans1.get(n);
				CompositeStateTran stateTran2 = intersectingTrans2.get(n);
				
				LPNTran lpnTran = stateTran1.getLPNTran();
				CompositeState nextSubState1 = stateTran1.getNextState();
				int[] nextState1Tuple = nextSubState1.getStateTuple();
				CompositeState nextSubState2 = stateTran2.getNextState();
				int[] nextState2Tuple = nextSubState2.getStateTuple();
				int[] newStateTuple = new int[size];

				for(int i = 0; i < sg1Size; i++){
					newStateTuple[i] = nextState1Tuple[i];
				}
				
				int index = 0;
				for(int i = sg1Size; i < size; i++){
					newStateTuple[i] = nextState2Tuple[indexArray[index++]];
				}
				
				CompositeState nextCompositeState = new CompositeState(newStateTuple);
				returnState = compositeSG.addState(nextCompositeState);
				if(returnState == nextCompositeState){
					StateError errorCode1 = nextSubState1.getErrorCode();
					StateError errorCode2 = nextSubState2.getErrorCode();
					if(errorCode1 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode1);
					}
					else if(errorCode2 != StateError.NONE){
						nextCompositeState.setErrorCode(errorCode2);
					}
					
					if(nextCompositeState.getErrorCode() == StateError.NONE){
						newStateStack.push(nextCompositeState);
						subStateStack1.push(nextSubState1);
						subStateStack2.push(nextSubState2);
					}
				}
				else{
					nextCompositeState = returnState;
				}
				
				CompositeStateTran newStateTran = compositeSG.addStateTran(currentState, nextCompositeState, lpnTran);
				if(!lpnList.contains(lpnTran.getLpn()) || !lpnList.containsAll(lpnTran.getDstLpnList())){
					newStateTran.setVisibility();
				}
				
				/*
				 * If the next state is an error state perform partial AFR.
				 * Set the current state as the error state, remove the current state's
				 * outgoing state transitions, remove new next states from the stack, 
				 * and remove unreachable states.
				 */
				if(Options.getAutoFailureFlag() && nextCompositeState.getErrorCode() != StateError.NONE
						&& lpnList.contains(lpnTran.getLpn())){
					currentState.setErrorCode(nextCompositeState.getErrorCode());
					
					CompositeStateTran[] a = currentState.getOutgoingStateTranList().
						toArray(new CompositeStateTran[currentState.numOutgoingTrans()]);
					for(CompositeStateTran stTran : a){
						CompositeState nextState = stTran.getNextState();
						
						// check if it is a newly generated next state
						if(nextState.numIncomingTrans() == 1 && nextState.getErrorCode() == StateError.NONE){							
							newStateStack.pop();
							subStateStack1.pop();
							subStateStack2.pop();
						}
						
						compositeSG.removeStateTran(stTran);
					}
					
					CompositionalMinimization.removeAllUnreachableStates(compositeSG);
					break;
				}
			}
			
			if(currentState.getErrorCode() == StateError.NONE && currentState.numOutgoingTrans() == 0){
				currentState.setErrorCode(StateError.DEADLOCK);
			}
			
			long curTotalMem = Runtime.getRuntime().totalMemory();
			if(curTotalMem > peakTotal){
				peakTotal = curTotalMem;
			}
			
			long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(curUsedMem > peakUsed){
				peakUsed = curUsedMem;
			}
		}
		
		if(Options.getVerbosity() > 0){
			System.out.println("\n   " + compositeSG.getLabel() + ": ");
			System.out.println("      --> # states: " + compositeSG.numStates());
			System.out.println("      --> # transitions: " + compositeSG.numStateTrans());
	
			System.out.println("\n      --> Peak used memory: " + peakUsed/1000000F + " MB");
			System.out.println("      --> Peak total memory: " + peakTotal/1000000F + " MB");
			System.out.println("      --> Final used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB");
			
			long elapsedTimeMillis = System.currentTimeMillis()-start; 
			float elapsedTimeSec = elapsedTimeMillis/1000F;
			System.out.println("      --> Elapsed time: " + elapsedTimeSec + " sec");
			
			if(elapsedTimeSec > 60){
				float elapsedTime = elapsedTimeSec;
				elapsedTime = elapsedTimeSec/(float)60;
				System.out.println("      --> Elapsed time: " + elapsedTime + " min");
			}
		}
		
		return compositeSG;
	}
	
	/**
	 * Performs parallel composition on the specified composite state graphs sg1 and sg2.
	 * Returns the resulting composite state graph sg1||sg2 if they are compatible.
	 * Parallel composition can only be performed if sg1 modifies an input of sg2 or vice versa.
	 * @param sg1 - Composite state graph
	 * @param sg2 - Composite state graph
	 * @return Resulting composite state graph sg1||sg2 if compatible for composition, otherwise NULL
	 */
	private static HashSet<IntArrayObj> compose(CompositeStateGraph[] csgArray, HashSet<IntArrayObj> globalStateSet){
		long start = System.currentTimeMillis(); 
		
		// Holds the starting index for each composite state graph in the global state vector
		int[] startIndexArray = new int[csgArray.length];
		
		// Total number of modules
		int totalSize = 0;
		
		for(int i=0; i < csgArray.length; i++){
			startIndexArray[i] = totalSize;
			totalSize += csgArray[i].getSize();
		}
		
		/*
		 * Create initial composite state
		 */
		int[] initModuleStates = new int[totalSize];
		CompositeState[] initCompositeStates = new CompositeState[csgArray.length];
		int index = 0;
		
		for(int i=0; i<csgArray.length; i++){
			initCompositeStates[i] = csgArray[i].getInitState();
			int[] sgInitTuple = csgArray[i].getInitState().getStateTuple();
			for(int j=0; j<sgInitTuple.length; j++){
				initModuleStates[index++] = sgInitTuple[j];
			}
		}
		
		// Initialize global state set
		globalStateSet.add(new IntArrayObj(initModuleStates));

		/*
		 * Check initial state for error
		 */
		for(int i=0; i<csgArray.length; i++){
			StateError ec = initCompositeStates[i].getErrorCode();
			if(ec != StateError.NONE){
				return globalStateSet;
			}
		}
		
		/*
		 * Build the final state graph's label
		 */
		String sgLabel = "";
		for(int i=0; i<csgArray.length; i++){
			sgLabel += csgArray[i].getLabel();
			
			if(i <= csgArray.length-2){
				sgLabel += "||";
			}
		}
		
		// Create CompositeState stack
		Stack<CompositeState[]> compositeStateStack = new Stack<CompositeState[]>();
		
		// Initialize with initial CompositeState
		compositeStateStack.push(initCompositeStates);
		
		long peakUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		long peakTotal = Runtime.getRuntime().totalMemory();
		
		while(!compositeStateStack.isEmpty()){
			CompositeState[] currentCompositeStates = compositeStateStack.pop();
			
//			System.out.println("CURRENT STATE: " + Arrays.toString(currentCompositeStates) + " - " + globalStateSet.size());
			
			int numOutgoingTrans = 0;
			for(int i=0; i<currentCompositeStates.length; i++){
				/*
				 * Get the enabled transitions and fire each independent transition
				 */
				CompositeState currentState = currentCompositeStates[i];
				CompositeStateGraph currentStateGraph = csgArray[i];
				
//				System.out.println("   " + csgArray[i].getLabel() + ": ");
				List<CompositeStateTran> outgoingTrans = currentState.getOutgoingStateTranList();
//				System.out.println("      " + outgoingTrans);
				
				for(int j=0; j<outgoingTrans.size(); j++){
					CompositeStateTran currentTran = outgoingTrans.get(j);
					
					// Check if the transition is visible
					if(!currentTran.visible()){
						int[] nextGlobalState = new int[totalSize];
						CompositeState nextState = currentTran.getNextState();
						int[] nextStateTuple = nextState.getStateTuple();
						
						index=0;
						for(int k=0; k<currentCompositeStates.length; k++){
							int[] stateTuple = currentCompositeStates[k].getStateTuple();
							for(int n=0; n<stateTuple.length; n++){
								nextGlobalState[index++] = stateTuple[n];
							}
						}
						
						index = startIndexArray[i];
						for(int k=0; k<nextStateTuple.length; k++){
							nextGlobalState[index++] = nextStateTuple[k];
						}
						
						/*
						 * If the next global state is new, add the next composite states to the stack
						 */
						if(globalStateSet.add(new IntArrayObj(nextGlobalState))){
							CompositeState[] nextCompositeStates = new CompositeState[currentCompositeStates.length];
							for(int k=0; k<currentCompositeStates.length; k++){
								nextCompositeStates[k] = currentCompositeStates[k];
							}
							
							nextCompositeStates[i] = nextState;
							compositeStateStack.push(nextCompositeStates);
						}
						
						numOutgoingTrans++;
					}
					else{
						/*
						 * For each output transition, find the tgt composite state graphs and get the next states
						 */
						if(currentStateGraph.containsLpn(currentTran.getLPNTran().getLpn())){
							int[] nextGlobalState = new int[totalSize];
							CompositeState nextState = currentTran.getNextState();
							int[] nextStateTuple = nextState.getStateTuple();
							
							CompositeState[] nextCompositeStates = new CompositeState[currentCompositeStates.length];
							
							index=0;
							for(int k=0; k<currentCompositeStates.length; k++){
								int[] stateTuple = currentCompositeStates[k].getStateTuple();
								for(int n=0; n<stateTuple.length; n++){
									nextGlobalState[index++] = stateTuple[n];
								}
								
								nextCompositeStates[k] = currentCompositeStates[k];
							}
							
							nextCompositeStates[i] = nextState;
							
							index = startIndexArray[i];
							for(int k=0; k<nextStateTuple.length; k++){
								nextGlobalState[index++] = nextStateTuple[k];
							}

							//TODO: error check if composite state graph does not have enabled transition
							
							/*
							 * Update next global state with the other composite state graph next states
							 */
							LPNTran lpnTran = currentTran.getLPNTran();
							for(int k=0; k<currentCompositeStates.length; k++){
								if(i == k){
									continue;
								}
								
//								System.out.println("         " + csgArray[k].getLabel());
									
								CompositeState s = currentCompositeStates[k];
								List<CompositeStateTran> trans = s.getOutgoingStateTranList();
								
//								System.out.println("            " + trans);
								
								for(int n=0; n<trans.size(); n++){
									CompositeStateTran t = trans.get(n);
									if(t.getLPNTran() == lpnTran){
										int[] nextTuple = t.getNextState().getStateTuple();
										
//										System.out.println("               FOUND: " + Arrays.toString(nextTuple));
										
										index = startIndexArray[k];
										for(int m=0; m<nextTuple.length; m++){
											nextGlobalState[index++] = nextTuple[m];
										}
										
										nextCompositeStates[k] = t.getNextState();
										
										break;
									}
								}
							}
							
							/*
							 * If the next global state is new, add the next composite states to the stack
							 */
							if(globalStateSet.add(new IntArrayObj(nextGlobalState))){
								compositeStateStack.push(nextCompositeStates);
							}
							
							numOutgoingTrans++;
						}
					}
				}
			}
			
			if(numOutgoingTrans == 0){
				int[] globalState = new int[totalSize];
				
				index = 0;
				for(int i=0; i<currentCompositeStates.length; i++){
					int[] stateTuple = currentCompositeStates[i].getStateTuple();
					for(int j=0; j<stateTuple.length; j++){
						globalState[index++] = stateTuple[j]; 
					}
				}
				
				System.out.println("Deadlock Error in global state " + Arrays.toString(globalState));
				return globalStateSet;
			}
			
			long curTotalMem = Runtime.getRuntime().totalMemory();
			if(curTotalMem > peakTotal){
				peakTotal = curTotalMem;
			}
			
			long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(curUsedMem > peakUsed){
				peakUsed = curUsedMem;
			}
		}
		
		if(Options.getVerbosity() > 0){
			System.out.println("\n   " + sgLabel + ": ");
			System.out.println("      --> # states: " + globalStateSet.size());
			System.out.println("      --> # transitions: ");
	
			System.out.println("\n      --> Peak used memory: " + peakUsed/1000000F + " MB");
			System.out.println("      --> Peak total memory: " + peakTotal/1000000F + " MB");
			System.out.println("      --> Final used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB");
			
			long elapsedTimeMillis = System.currentTimeMillis()-start; 
			float elapsedTimeSec = elapsedTimeMillis/1000F;
			System.out.println("      --> Elapsed time: " + elapsedTimeSec + " sec");
			
			if(elapsedTimeSec > 60){
				float elapsedTime = elapsedTimeSec;
				elapsedTime = elapsedTimeSec/(float)60;
				System.out.println("      --> Elapsed time: " + elapsedTime + " min");
			}
		}
		
		return globalStateSet;
	}
	
	/**
	 * Finds a trace from a state graph's initial state to the specified error state.
	 * @param sg - State graph
	 * @param errorState - Error state
	 */
	private static void errorTrace(CompositeStateGraph sg, CompositeState errorState){
		CompositeState initialState = sg.getInitState();
		
		Set<Integer> stateSet = new HashSet<Integer>();
		Stack<CompositeStateTran> tranStack = new Stack<CompositeStateTran>();
		
		stateSet.add(initialState.getIndex());
		
		recTrace(initialState, errorState, stateSet, tranStack);
		
		System.out.println("\nError Trace:");
		
		StateGraph[] sgTuple = sg.getStateGraphArray();
		for(CompositeStateTran stTran : tranStack){
			CompositeState currentState = stTran.getCurrentState();
			
			System.out.print("    " + currentState.getIndex() + " (");
			
			int[] stateTuple = currentState.getStateTuple();
			for(int i = 0; i < sgTuple.length; i++){
				if(i < sgTuple.length-1)
					System.out.print(sgTuple[i].getLpn().getLabel() + "." + stateTuple[i] + ", ");
				else
					System.out.print(sgTuple[i].getLpn().getLabel() + "." + stateTuple[i]);
			}
			
			System.out.println(") --" + stTran.getLPNTran().getFullLabel() + "--> " + stTran.getNextState().getIndex());
		}
		
		System.out.println();
	}
	
	/**
	 * Recursively finds a path to the error state.
	 * @param currentState - Current state
	 * @param errorState - Error state
	 * @param stateSet - Set of state already traversed
	 * @param tranStack - Stack of state transitions containing the path to the error state
	 * @return TRUE if the currentState contains a path to the error state, otherwise FALSE
	 */
	private static boolean recTrace(CompositeState currentState, CompositeState errorState, Set<Integer> stateSet, 
			Stack<CompositeStateTran> tranStack){
		
		for(CompositeStateTran stTran : currentState.getOutgoingStateTranList()){
			CompositeState nextState = stTran.getNextState();
			
			tranStack.push(stTran);
			
			if(stateSet.add(nextState.getIndex())){
				if(nextState == errorState){
					return true;
				}
				else if(recTrace(nextState, errorState, stateSet, tranStack)){
					return true;
				}
			}
			
			tranStack.pop();
		}
		
		return false;
	}
	
	/**
	 * Compositional search is performed to find the state space for each specified module.
	 * Parallel composition and compositional minimization, if the option is set, are performed
	 * to find the final state graph.
	 * @param designUnitSet - Set of module state graphs
	 */
	public static void findReducedSG(StateGraph[] designUnitSet) {
		System.out.println("---> Start compositional minimization process ...");
		
		// Statistical information
		long start = System.currentTimeMillis(); 
		long peakTotal = 0;
		long peakUsed = 0;
		int largestSG = 0;
		
//		BufferedReader reader9 = new BufferedReader(new InputStreamReader(System.in));
//        try {
//			reader9.readLine();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
		
		/*
		 * Perform compositional search
		 */
		if(Options.getSearchType() == Options.SearchTypeDef.CRA_LOCAL){
			System.out.print("8888888888888888");
			craLocalSearch(designUnitSet);
		}
		else if(Options.getSearchType() == Options.SearchTypeDef.CRA_GLOBAL){
			//craGlobalSearch(designUnitSet);
			craGlobalSearch1(designUnitSet);
			return;
		}
		else{
			craConstr(designUnitSet);
		}
		
		/*
		 * Draw each module's state graph if the option is set
		 */
		if(Options.drawModuleStateGraphs()){
			for(StateGraph sg : designUnitSet){
				sg.draw();
			}
		}
		
//		BufferedReader reader1 = new BufferedReader(new InputStreamReader(System.in));
//        try {
//			reader1.readLine();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}

		// List to hold the composite state graph for each module
		List<CompositeStateGraph> moduleList = new ArrayList<CompositeStateGraph>();
		
		System.out.println();
		
		/*
		 * For each module's state graph convert to a composite state graph and perform
		 * compositional minimization is the option is set.
		 */
		for(StateGraph sg : designUnitSet){
			if(Options.getVerbosity() > 0){
				System.out.println("\n   ---------------------------------");
			}
			
			/*
			 * convert SG to composite SG
			 */
			CompositeStateGraph csg = new CompositeStateGraph(sg);
			
			/*
			 * Finish AFR by propagating the error states to the input transitions
			 */
			if(Options.getAutoFailureFlag() == true){
				propagateErrorStates(csg);
			}
			
			// keep track of the largest SG
			if(csg.numStates() > largestSG){
				largestSG = csg.numStates();
			}
			
			/*
			 * Check if a failure is inevitable.
			 */
			if(failure(csg)){
				return;
			}
			
			/*
			 * Perform compositional minimization if the option is set
			 */
			if(Options.getCompositionalMinimization() == CompMinDef.ABSTRACTION){
				/*
				 * Perform transition based abstraction
				 */
				if(containsInvisibleTransition(csg)){
					performAbstractionAndReduction(csg);
					
					/*
					 * Check if a failure is inevitable.
					 */
					if(failure(csg)){
						return;
					}
				}
			}
			
			/*
			 * clear unnecessary attributes from StateGraph
			 */
			if(Options.getClearMemoryFlag()){
				sg.clearTrans();
			}
			
			// add composite state graph to the list of module composite state graphs
			moduleList.add(csg);	
			
			long curTotalMem = Runtime.getRuntime().totalMemory();
			if(curTotalMem > peakTotal){
				peakTotal = curTotalMem;
			}
			
			long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(curUsedMem > peakUsed){
				peakUsed = curUsedMem;
			}
			
			if(Options.getVerbosity() > 0){
				System.out.println("   ---------------------------------\n");
			}
		}
		
		/*
		 * Clear attributes used during compositional search
		 */
		clearAttributes();
		
		if(Options.getVerbosity() > 0){
			long totalMillis1 = System.currentTimeMillis()-start; 
			float totalSec1 = totalMillis1/1000F;
			System.out.println("\n   *** Elapsed Time: " + totalSec1 + " sec ***");
			
			if(totalSec1 > 60){
				float totalTime = totalSec1/(float)60;
				System.out.println("   *** Elapsed Time: " + totalTime + " min ***");
			}
			
				
			
			int states = 0;
			int trans = 0;
			for(CompositeStateGraph s : moduleList){
				states += s.numStates();
				trans += s.numStateTrans();
			}
			
			System.out.println("   *** # States: " + states + " ***");
			System.out.println("   *** # State Transitions: " + trans + " ***");
			
			System.out.println("   *** Used Memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB ***\n");
		}
		
//		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
//        try {
//			reader.readLine();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
		
		HashSet<IntArrayObj> globalStateSet = new HashSet<IntArrayObj>();
		CompositeStateGraph[] sgArray = null;
		
		
		/*
		 * If the user specifies an order for parallel composition,
		 * use orderedFindReducedSG() instead.
		 */
		if(Options.getCompositionOrder().length() > 0){
			sgArray = orderedFindReducedSG(designUnitSet, moduleList, globalStateSet);
			if(sgArray == null){
				return;
			}
		}
		else{	
			// Holds the latest composite state graph
			CompositeStateGraph csg = null;
			
			// Initialize with the the first CSG in the list
			if(moduleList.size() > 0){
				csg = moduleList.get(0);
				moduleList.remove(0);
			}
	
			/*
			 * Compose the first available module state graph, then perform minimization if the option is set
			 */
			while(moduleList.size() > 1){
				CompositeStateGraph tmpSG = null;
				
				if(Options.getVerbosity() > 0){
					System.out.println("\n   ---------------------------------");
				}
				
				/*
				 * Find the first compatible SG to compose with
				 */
				CompositeStateGraph sg2 = null;
				for(int i = 0; i < moduleList.size(); i++){
					sg2 = moduleList.get(i);
					
					if(Options.getVerbosity() > 0){
						System.out.println("   Composing " + csg.getLabel() + " with " + sg2.getLabel() + "...");
					}
					
					tmpSG = compose(csg, sg2);
					if(tmpSG != null){
						break;
					}
				}
				
				moduleList.remove(sg2);
				csg = tmpSG;
				if(csg == null){
					System.exit(1);
				}
				
				/*
				 * Finish AFR by propagating the error states to the input transitions
				 */
				if(Options.getAutoFailureFlag() == true){
					propagateErrorStates(csg);
				}
				
				// keep track of the largest SG
				if(csg.numStates() > largestSG){
					largestSG = csg.numStates();
				}
				
				/*
				 * Check if a failure is inevitable.
				 */
				if(failure(csg)){
					return;
				}
	
				/*
				 * Perform compositional minimization if the option is set.
				 */
				if(Options.getCompositionalMinimization() == CompMinDef.ABSTRACTION){
					if(containsInvisibleTransition(csg)){
						performAbstractionAndReduction(csg);
						
						/*
						 * Check if a failure is inevitable.
						 */
						if(failure(csg)){
							return;
						}
					}
				}
				
				long curTotalMem = Runtime.getRuntime().totalMemory();
				if(curTotalMem > peakTotal){
					peakTotal = curTotalMem;
				}
				
				long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				if(curUsedMem > peakUsed){
					peakUsed = curUsedMem;
				}
				
				if(Options.getVerbosity() > 0){
					System.out.println("   ---------------------------------\n");
				}
			}
			
			/*
			 * Compose the current composite state graph with the final module state graph
			 */
			CompositeStateGraph sg2 = moduleList.get(0);
			
			if(Options.getVerbosity() > 0){
				System.out.println("\n   ---------------------------------");
				System.out.println("   Composing " + csg.getLabel() + " with " + sg2.getLabel() + "...");
			}
			
			sgArray = new CompositeStateGraph[2];
			sgArray[0] = csg;
			sgArray[1] = sg2;
			
			globalStateSet = compose(sgArray, globalStateSet);
		}
		
		if(Options.getVerbosity() > 0){
			System.out.println("   ---------------------------------\n");
		}
		
		/*
		 * Error check global state set
		 */
		if(!findError(globalStateSet, sgArray)){
			// Print the final state graph info
			printGlobalStateGraph(globalStateSet, sgArray);
		}
		
//		/*
//		 * Draw the final state graph, if the option is set
//		 */
//		if(Options.drawFinalStateGraph()){
//			csg.draw();
//		}
		
//		/*
//		 * Determine if an error exists in the final state graph.
//		 * If so, print an error message and an error trace.
//		 */
//		CompositeState errorState = containsError(csg);
//		if(errorState != null){
//			printErrorState(csg, errorState);
//			errorTrace(csg, errorState);
//		}

		long curTotalMem = Runtime.getRuntime().totalMemory();
		if(curTotalMem > peakTotal){
			peakTotal = curTotalMem;
		}
		
		long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
		if(curUsedMem > peakUsed){
			peakUsed = curUsedMem;
		}
		
		if(Options.getVerbosity() > 0){
			long totalMillis = System.currentTimeMillis()-start; 
			float totalSec = totalMillis/1000F;
			System.out.println("\n****** Total Elapsed Time: " + totalSec + " sec ******");
			
			if(totalSec > 60){
				float totalTime = totalSec/(float)60;
				System.out.println("****** Total Elapsed Time: " + totalTime + " min ******");
			}
			
			System.out.println("****** Peak Memory Used: " + peakUsed/1000000F + " MB ******");
			System.out.println("****** Peak Memory Total: " + peakTotal/1000000F + " MB ******");
			System.out.println("****** Lastest SG: " + largestSG + " states ******");
			System.out.println();
		}
		
		System.out.println("\n********************************************");
	}
	
	private static boolean failure(CompositeStateGraph csg){
		if(csg.numStates() > csg.numStateTrans()){
			CompositeState errorState = containsError(csg);
			if(errorState != null){
				printErrorState(csg, errorState);
				errorTrace(csg, errorState);
			}
			
			return true;
		}
		
		return false;
	}
	
	private static void performAbstractionAndReduction(CompositeStateGraph csg){
		/*
		 * Perform transition based abstraction
		 */
		if(Options.getVerbosity() > 0){
			System.out.println("\n   " + csg.getLabel() + ": transitionBasedAbstraction");
		}
		
		CompositionalMinimization.transitionBasedAbstraction(csg);
		
//		// keep track of the largest SG
//		if(csg.numStates() > largestSG){
//			largestSG = csg.numStates();
//		}
		
		/*
		 * Check if a failure is inevitable.
		 */
		if(csg.numStates() > csg.numStateTrans()){
			return;
		}
		

		/*
		 * Remove equivalent failure transitions
		 */
		if(Options.getEquivFailureFlag()){
			equivFailureTranRemoval(csg);
		}
		
		/*
		 * Check if a failure is inevitable.
		 */
		if(csg.numStates() > csg.numStateTrans()){
			return;
		}
		
		/*
		 * Merge states with equivalent incoming state transitions
		 */
		if(Options.getVerbosity() > 0){
			System.out.println("\n   " + csg.getLabel() + ": mergeIncoming");
		}
		
		CompositionalMinimization.mergeIncoming(csg);

//		// keep track of the largest SG
//		if(csg.numStates() > largestSG){
//			largestSG = csg.numStates();
//		}
		
		/*
		 * Check if a failure is inevitable.
		 */
		if(csg.numStates() > csg.numStateTrans()){
			return;
		}
		
		/*
		 * Merge states with equivalent outgoing state transitions (redundant states)
		 */
		if(Options.getVerbosity() > 0){
			System.out.println("\n   " + csg.getLabel() + ": mergeOutgoing");
		}
		
		CompositionalMinimization.mergeOutgoing2(csg);
		
//		// keep track of the largest SG
//		if(csg.numStates() > largestSG){
//			largestSG = csg.numStates();
//		}
	}
	
	private static boolean containsInvisibleTransition(CompositeStateGraph sg){
		for(CompositeState s : sg.getStateSet()){
			for(CompositeStateTran t : s.getOutgoingStateTranList()){
				if(!t.visible()){
					return true;
				}
			}
		}
		
		return false;
	}
	
	private static void printGlobalStateGraph(Set<IntArrayObj> globalStateSet, CompositeStateGraph[] sgArray){
		String sgLabel = sgArray[0].getLabel();
		for(int i=1; i<sgArray.length; i++){
			sgLabel += ("||" + sgArray[i].getLabel());
		}
		
		System.out.println("\n   =================================");
		System.out.println("   Global State Graph " + sgLabel + ": ");
		System.out.println("      --> # states: " + globalStateSet.size());
		System.out.println("      --> # transitions: ");
		System.out.println("   ==================================\n");
	}
	
	/**
	 * Compositional search is performed to find the state space for each specified module.
	 * Parallel composition and compositional minimization, if the option is set, are performed
	 * to find the final state graph.  Parallel composition is performed is the order specified
	 * by the option COMPOSITION_ORDER.
	 * @param designUnitSet - Set of module state graphs
	 */
	private static CompositeStateGraph[] orderedFindReducedSG(StateGraph[] designUnitSet, List<CompositeStateGraph> moduleList, HashSet<IntArrayObj> globalStateSet) {			
		// Map containing each module's composite state graph
		HashMap<String, CompositeStateGraph> moduleMap = new HashMap<String, CompositeStateGraph>();
		for(CompositeStateGraph moduleSG : moduleList){
			moduleMap.put(moduleSG.getLabel(), moduleSG);
		}
		
		/*
		 * Setup the pattern and matcher to parse the module ordering
		 */
		Pattern p = Pattern.compile("[\\(][\\w.]+((\\s)*,(\\s)*[\\w.]+)*[\\)]");
		Matcher m = p.matcher(Options.getCompositionOrder());
		
		/*
		 * Determine the number of groups specified
		 */
		int numGroups = 0;
		boolean match = m.find();
		while(match){
			numGroups++;
			match = m.find();
		}
		
		/*
		 * Reset the pattern matcher to the beginning
		 */
		match = m.find(0);
		
		CompositeStateGraph[] csgGroupArray = new CompositeStateGraph[numGroups];
		
		for(int i=0; i<numGroups; i++){
			String s = m.group();
			csgGroupArray[i] = groupFindReducedSG(s.substring(1, s.length()-1).trim(), moduleMap);
			
			/*
			 * Check if a failure is inevitable.
			 */
			if(failure(csgGroupArray[i])){
				return null;
			}
			
			// Advance to the next group
			m.find();
		}
		
		if(Options.getVerbosity() > 0){
			System.out.println("\n   ---------------------------------");
		}
		
		if(Options.getVerbosity() > 0){
			System.out.print("   Composing ");
			for(int i=0; i< numGroups; i++){
				System.out.print(csgGroupArray[i].getLabel());
				
				if(i<numGroups-2){
					System.out.print(", ");
				}
				else if(i == numGroups-2){
					System.out.print(", and ");
				}
			}
			
			System.out.println("...");
		}
		
		/*
		 * Compose all of the group state graphs together
		 */
		compose(csgGroupArray, globalStateSet);
		
		return csgGroupArray;
	}
	
	private static CompositeStateGraph groupFindReducedSG(String groupOrder, HashMap<String, CompositeStateGraph> moduleMap){
		/*
		 * Parse through the group's modules
		 */
		String[] orderArray = groupOrder.split("[,]");
		for(int i = 0; i < orderArray.length; i++){
			orderArray[i] = orderArray[i].trim();
		}
		
		// Group state graph
		CompositeStateGraph csg = null;

		// Initialize to the first module in the specified order.
		if(orderArray[0].startsWith("(")){
			String s = orderArray[0];
			csg = groupFindReducedSG(s.substring(1, s.length()-1).trim(), moduleMap);
		}
		else{
			csg = moduleMap.get(orderArray[0]);
		}
		
		if(csg == null){
			System.err.println("error: module " + orderArray[0] + " not found");
			System.exit(1);
		}
		
		/*
		 * Compose in the order specified, then perform compositional minimization if the option is set
		 */
		for(int i = 1; i < orderArray.length; i++){
			if(Options.getVerbosity() > 0){
				System.out.println("\n   ---------------------------------");
			}
			
			CompositeStateGraph csg2 = null;
			
			// get the next module to compose
			if(orderArray[i].startsWith("(")){
				String s = orderArray[i];
				csg2 = groupFindReducedSG(s.substring(1, s.length()-1).trim(), moduleMap);
				
				/*
				 * Check if a failure is inevitable.
				 */
				if(csg.numStates() > csg.numStateTrans()){
					return csg2;
				}
			}
			else{
				csg2 = moduleMap.get(orderArray[i]);
			}
			
			if(csg2 == null){
				System.err.println("error: module " + orderArray[i] + " not found");
				System.exit(1);
			}
			
			/*
			 * Perform parallel composition
			 */
			
			if(Options.getVerbosity() > 0){
				System.out.println("   Composing " + csg.getLabel() + " with " + csg2.getLabel() + "...");
			}
			
			csg = compose(csg, csg2);
			if(csg == null){
				System.exit(1);
			}
			
			/*
			 * Finish AFR by propagating the error states to the input transitions
			 */
			if(Options.getAutoFailureFlag() == true){
				propagateErrorStates(csg);
			}
			
			
			/*
			 * Check if a failure is inevitable.
			 */
			if(csg.numStates() > csg.numStateTrans()){
				return csg;
			}
			
			/*
			 * Perform compositional minimization if the option is set.
			 */
			if(Options.getCompositionalMinimization() == CompMinDef.ABSTRACTION){
				if(containsInvisibleTransition(csg)){
					performAbstractionAndReduction(csg);
					
					/*
					 * Check if a failure is inevitable.
					 */
					if(csg.numStates() > csg.numStateTrans()){
						return csg;
					}
				}
			}
			
			if(Options.getVerbosity() > 0){
				System.out.println("   ---------------------------------\n");
			}
		}
		
		return csg;
	}
	
	/**
	 * Propagate error states back to the input transitions.
	 * @param sg - State graph to process
	 */
	private static void propagateErrorStates(CompositeStateGraph sg){
		List<CompositeState> errorStates = new LinkedList<CompositeState>();
		CompositeState initState = sg.getInitState();
		
		/*
		 * List of LPNs composing the state graph
		 */
		List<LPN> lpnList = new ArrayList<LPN>(sg.getSize());
		for(StateGraph m : sg.getStateGraphArray()){
			lpnList.add(m.getLpn());
		}
		
		/*
		 * For each error state follow the incoming paths until an input transition 
		 * or the initial state is reached
		 */
		for(CompositeState errorState : sg.getStateSet()){
			if(errorState.getErrorCode() == StateError.NONE){
				continue;
			}
			
			/*
			 * Create a new state stack and initialize with the error state
			 */
			Stack<CompositeState> stateStack = new Stack<CompositeState>();
			stateStack.push(errorState);
			
			/*
			 * Traverse incoming state transitions until an input transition is reached.
			 * Set the input transition's next state as the error state.
			 * Remove invisible transitions as they are traversed.
			 */
			while(!stateStack.empty()){
				CompositeState currentState = stateStack.pop();
				
				/*
				 * If the initial state is reached before an input transition, 
				 * make the initial state the error state
				 */
				if(currentState == initState){
					currentState.setErrorCode(errorState.getErrorCode());
					
					// remove outgoing state transitions
					for(CompositeStateTran tran : currentState.getOutgoingStateTranList().toArray(new CompositeStateTran[currentState.numOutgoingTrans()])){
						sg.removeStateTran(tran);
					}
					
					errorStates.add(currentState);
					
					break;
				}
				
				for(CompositeStateTran stTran : currentState.getIncomingStateTranList().toArray
						(new CompositeStateTran[currentState.numIncomingTrans()])){
					
					/*
					 * If it is an input transitions, set the next state as the error state.
					 * Add non-input transitions to the stack for traversal.
					 */
					if(stTran.visible() && !lpnList.contains(stTran.getLPNTran().getLpn())){
						currentState.setErrorCode(errorState.getErrorCode());
						
						// remove outgoing state transitions
						for(CompositeStateTran tran : currentState.getOutgoingStateTranList().toArray(new CompositeStateTran[currentState.numOutgoingTrans()])){
							sg.removeStateTran(tran);
						}
						
						errorStates.add(currentState);
					}
					else{
						stateStack.push(stTran.getCurrentState());
						sg.removeStateTran(stTran);
					}
				}
			}
		}
		
		/*
		 * Remove all unreachable states
		 */
		CompositionalMinimization.removeAllUnreachableStates(sg);
	}
	
	/**
	 * Removes equivalent failure state transitions.
	 * @param sg - State graph to remove equivalent failure state transitions from
	 */
	private static void equivFailureTranRemoval(CompositeStateGraph sg){
		/*
		 * For each error states check if an equivalent failure transitions exist,
		 * and remove any occurrences.
		 */
		for(CompositeState errorState : sg.getStateSet().toArray(new CompositeState[sg.numStates()])){
			if(errorState.getErrorCode() == StateError.NONE){
				continue;
			}
			
			/*
			 * For each error transition check if the current state has a non-deterministic
			 * transition to a non-error state.
			 */
			List<CompositeStateTran> errorTrans = errorState.getIncomingStateTranList();
			for(int i=0; i< errorTrans.size(); i++){
				CompositeStateTran errorTran = errorTrans.get(i);
				CompositeState currentState = errorTran.getCurrentState();
				LPNTran errorLpnTran = errorTran.getLPNTran();
				
				for(CompositeStateTran tran : currentState.getOutgoingStateTranList().toArray(new CompositeStateTran[currentState.numOutgoingTrans()])){
					if(errorTran == tran){
						continue;
					}
					
					CompositeState nextState = tran.getNextState();
					if(tran.getLPNTran() == errorLpnTran && nextState.getErrorCode() == StateError.NONE){
						// remove equivalent failure transition
						sg.removeStateTran(tran);
						
						// remove unreachable state
						if(nextState.numIncomingTrans() == 0){
							CompositionalMinimization.removeUnreachableState(sg, currentState);
						}
					}
				}
			}
		}
	}
	
	/**
	 * Determines whether a composite state graph contains an error
	 * @param sg - Composite state graph to check
	 * @return A composite state containing an error, otherwise null
	 */
	private static CompositeState containsError(CompositeStateGraph sg){
		if(sg == null){
			return null;
		}
		
		for(CompositeState state : sg.getStateSet()){
			if(state.getErrorCode() != StateError.NONE && state.getErrorCode() != StateError.DEADLOCK){
				return state;
			}
		}
		
		for(CompositeState state : sg.getStateSet()){
			if(state.numOutgoingTrans() == 0){
				state.setErrorCode(StateError.DEADLOCK);
				return state;
			}
		}
		
		return null;
	}
	
	/**
	 * Determines whether a composite state graph contains an error
	 * @param globalStateSet - The design's global state set
	 */
	private static boolean findError(HashSet<IntArrayObj> globalStateSet, CompositeStateGraph[] csgGroupArray){	
		int size = 0;
		for(int i=0; i<csgGroupArray.length; i++){
			size += csgGroupArray[i].getSize();
		}
		
		StateGraph[] sgArray = new StateGraph[size];
		
		int index = 0;
		for(int i=0; i<csgGroupArray.length; i++){
			StateGraph[] sgTuple = csgGroupArray[i].getStateGraphArray();
			for(int j=0; j<sgTuple.length; j++){
				sgArray[index++] = sgTuple[j];
			}
		}
		
		for(IntArrayObj globalState : globalStateSet){
			int[] indexArray = globalState.toArray();
			
			for(int i=0; i<indexArray.length; i++){
				StateGraph sg = sgArray[i];
				int stateIndex = indexArray[i];
				State s = sg.getState(stateIndex);
				
				StateError e = s.getErrorCode();
				if(e != StateError.NONE){
					if(e == StateError.ASSERTION){
						System.out.println("Assertion Error in global state " + Arrays.toString(indexArray));
					}
					else if(e == StateError.DEADLOCK){
						System.out.println("Deadlock Error in global state " + Arrays.toString(indexArray));
					}
					else if(e == StateError.DISABLING){
						System.out.println("Disabling Error in global state " + Arrays.toString(indexArray));
					}
					else if(e == StateError.EXPRESSION){
						System.out.println("Expression Error in global state " + Arrays.toString(indexArray));
					}
					else{
						System.out.println("Livelock Error in global state " + Arrays.toString(indexArray));
					}
					
					return true;
				}
				
			}
		}
		
		return false;
	}
	
	/**
	 * Prints an error message for the specified error state.
	 * @param sg - Composite state graph
	 * @param errorState - Error state
	 */
	private static void printErrorState(CompositeStateGraph sg, CompositeState errorState){
		if(errorState != null && errorState.getErrorCode() != StateError.NONE){
			String errorMsg = "";
			StateError errorCode = errorState.getErrorCode();
			
			if(errorCode == StateError.ASSERTION){
				errorMsg = "Error: assertion error in state " + errorState.getIndex();
			}
			else if(errorCode == StateError.DEADLOCK){
				errorMsg = "Error: deadlock in state " + errorState.getIndex();
			}
			else if(errorCode == StateError.DISABLING){
				errorMsg = "Error: disabling error in state " + errorState.getIndex();
			}
			else if(errorCode == StateError.EXPRESSION){
				errorMsg = "Error: expression in state " + errorState.getIndex();
			}
			else if(errorCode == StateError.LIVELOCK){
				errorMsg = "Error: livelock in state " + errorState.getIndex();
			}
			else{
				System.out.flush();
				System.err.println("Error: invalid state error type");
				System.exit(1);
			}
			
			errorMsg += " (";
			
			int[] stateTuple = errorState.getStateTuple();
			StateGraph[] sgTuple = sg.getStateGraphArray();
			for(int i = 0; i < stateTuple.length; i++){
				errorMsg += sgTuple[i].getLpn().getLabel() + "." + stateTuple[i];
				if(i < stateTuple.length-1){
					errorMsg += ", ";
				}
			}
			
			errorMsg += ")";
			System.out.println("\n **** " + errorMsg + " ****");
		}
	}

	/**
	 * Adds constraint to the current set of constraints.
	 * @param c - Constraint to be added.
	 * @return True if added, otherwise false.
	 */
	private static boolean addConstraint(StateGraph sg, Constraint c){
		int index = sg.getLpn().getIndex();
		
		if(constraintSetList.get(index).add(c)){
			frontierConstraintList.get(index).add(c);
			return true;
		}
		
		return false;
	}

	/**
	 * Adds constraint to the current set of constraints.  
	 * Synchronized version of addConstraint().
	 * @param c - Constraint to be added.
	 * @return True if added, otherwise false.
	 */
	private static synchronized boolean synchronizedAddConstraint(StateGraph sg, Constraint c){
		int index = sg.getLpn().getIndex();
		
		if(constraintSetList.get(index).add(c)){
			frontierConstraintList.get(index).add(c);
			return true;
		}
		
		return false;
	}
	
	/**
	 * Returns the current list of 'old' constraints.  
	 * That is the set of constraints generated in ALL the previous iterations of compositional analysis.
	 * @param sg - State graph
	 * @return Current list of old constraints
	 */
	public static List<Constraint> getOldConstr(StateGraph sg){
		int index = sg.getLpn().getIndex();
		return oldConstraintList.get(index);
	}
	  
	/**
	 * Returns the current list of 'new' constraints.
	 * That is the set of constraints generated in the current iteration of compositional analysis.
	 * @param sg - State graph
	 * @return Current list of new constraints
	 */
	public static List<Constraint> getNewConstr(StateGraph sg){
		int index = sg.getLpn().getIndex();
		return newConstraintList.get(index);
	}
	
	/**
	 * Updates the different sets of constraints (new, old, frontier) after an 
	 * iteration of compositional analysis.
	 * @param sg - State graph
	 */
	private static void updateConstraints(StateGraph sg){
		int sgIndex = sg.getLpn().getIndex();
		
		List<Constraint> newConstr = newConstraintList.get(sgIndex);
		List<Constraint> frontierConstr = frontierConstraintList.get(sgIndex);
		
		{//if(Options.getSearchType() == SearchTypeDef.CRA){
			oldConstraintList.get(sgIndex).addAll(newConstr);
			newConstr.clear();
			newConstr.addAll(frontierConstr);
			frontierConstr.clear();
		}/*
		else{
			oldConstraintList.get(index).addAll(frontierConstr);
			frontierConstr.clear();
		}*/
	}
	
	/**
	 * Returns the current size of the constraint set.
	 * @param sg - State graph
	 * @return Size of the constraint set
	 */
	private static int numConstraints(StateGraph sg){
		int index = sg.getLpn().getIndex();
		
		Set<Constraint> constrSet = constraintSetList.get(index);
    	if(constrSet == null){
    		return oldConstraintList.get(index).size();
		}
	
    	return constrSet.size();
	}
	
	/**
	 * Returns the current list of 'frontier' states.
	 * That is the set of states that were added in the last iteration of compositional analysis.
	 * @param - State graph
	 * @return Current List of frontier states
	 */
    public static List<State> getFrontierStateSet(StateGraph sg){
    	int index = sg.getLpn().getIndex();
    	return frontierStateSetList.get(index);
    }
    
    /**
     * Returns the current set of states except for those which were added in the current 
     * or last iteration of compositional analysis.
     * @param sg - State graph
     * @return Current set of states
     */
    public static List<State> getStateSet(StateGraph sg){
    	int index = sg.getLpn().getIndex();
    	return stateSetList.get(index);
    }
    
    /**
     * Adds a state to the current set of 'frontier' states after the frontier set is updated.
     * @param sg - State graph
     * @param st - LPN state
     */
    private static void addFrontierState(StateGraph sg, State st){
    	int index = sg.getLpn().getIndex();
    	entryStateSetList.get(index).add(st);
    }

    /**
     * Updates the state graph's state frontier set after an iteration of compositional analysis.
     * @param sg - State graph
     */
    private static void updateStateFrontier(StateGraph sg){
    	int index = sg.getLpn().getIndex();
    	List<State> frontierSet = frontierStateSetList.get(index);
    	List<State> entrySet = entryStateSetList.get(index);
    	
    	{//if(Options.getSearchType() == SearchTypeDef.CRA){
	    	stateSetList.get(index).addAll(frontierSet);
	    	frontierSet.clear();
	    	frontierSet.addAll(entrySet);
	    	entrySet.clear();
    	}
    	/*
    	else{
    		stateSetList.get(index).addAll(entrySet);
	    	entrySet.clear();
    	}
    	*/
    }
    
    /**
     * Initializes all the static attributes for use with the design unit set.
     * @param designUnitSet - Array of state graphs
     */
	private static void setupAttributes(StateGraph[] designUnitSet){
		int size = designUnitSet.length;
		
		oldConstraintList = new ArrayList<List<Constraint>>(size);
		newConstraintList = new ArrayList<List<Constraint>>(size);
		frontierConstraintList = new ArrayList<List<Constraint>>(size);
		constraintSetList = new ArrayList<Set<Constraint>>(size);
		
		stateSetList = new ArrayList<List<State>>(size);
		frontierStateSetList = new ArrayList<List<State>>(size);
		entryStateSetList = new ArrayList<List<State>>(size);
		
		interfacePairList = new ArrayList<List<Pair<int[], int[]>>>(size);
		
		for(int i = 0; i < size; i++){
			oldConstraintList.add(new LinkedList<Constraint>());
			newConstraintList.add(new LinkedList<Constraint>());
			frontierConstraintList.add(new LinkedList<Constraint>());
			constraintSetList.add(new HashSet<Constraint>());
			
			stateSetList.add(new ArrayList<State>());
			frontierStateSetList.add(new ArrayList<State>());
			entryStateSetList.add(new LinkedList<State>());
			
			List<Pair<int[], int[]>> interfaceList = new ArrayList<Pair<int[], int[]>>(size);
			for(int j = 0; j < size; j++){
				interfaceList.add(emptyPair);
			}
			
			interfacePairList.add(interfaceList);
		}
	}
	
	 /**
     * Initializes all the static attributes for use with the design unit set.
     * @param designUnitSet - Array of state graphs
     */
	private static void clearAttributes(){
		
		for(int i = 0; i < oldConstraintList.size(); i++){
			oldConstraintList.get(i).clear();
			newConstraintList.get(i).clear();
			frontierConstraintList.get(i).clear();
			constraintSetList.get(i).clear();
			stateSetList.get(i).clear();
			frontierStateSetList.get(i).clear();
			entryStateSetList.get(i).clear();
			interfacePairList.get(i).clear();
		}
		
		oldConstraintList.clear();
		newConstraintList.clear();
		frontierConstraintList.clear();
		constraintSetList.clear();
		stateSetList.clear();
		frontierStateSetList.clear();
		entryStateSetList.clear();
		interfacePairList.clear();
	}

	/**
	 * Given two state graphs, performs local parallel search and returns the number of new state transitions created.
	 * @param sg1 - State Graph
	 * @param sg2 - State Graph
	 * @return The number of new state transitions created.
	 */
	public static int localSearch(StateGraph sg1, StateGraph sg2){
		LPN lpn1 = sg1.getLpn();
		LPN lpn2 = sg2.getLpn();
		
		int numStateTransBefore1 = sg1.numTransitions();
		int numStateTransBefore2 = sg2.numTransitions();
		
		long startTime = System.currentTimeMillis();
		
		/*
		 * Find the set of LPN transitions that should be synchronized between the two modules (common input LPNTrans), 
		 * and the set of LPN transitions that modify the other module.
		 */
		HashSet<LPNTran> modifyingTrans = new HashSet<LPNTran>();
		HashSet<LPNTran> synchronousTrans = new HashSet<LPNTran>();
		
		List<LPNTran> trans1 = lpn1.getTransitions().clone();
		trans1.retainAll(lpn2.getInputTranSet());
		modifyingTrans.addAll(trans1);
		
		List<LPNTran> trans2 = lpn2.getTransitions().clone();
		trans2.retainAll(lpn1.getInputTranSet());
		modifyingTrans.addAll(trans2);
	
		
		List<LPNTran> trans3 = new LinkedList<LPNTran>();
		trans3.addAll(lpn1.getInputTranSet());
		trans3.retainAll(lpn2.getInputTranSet());
		synchronousTrans.addAll(trans3);
		
		
		// Holds the set of local state pairs
		SetIntTuple stateSet = new MddTable(2);
			
		// Initial local state
		int[] initStateIdxArray = new int[2];
		initStateIdxArray[0] = sg1.getInitialState().getIndex();
		initStateIdxArray[1] = sg2.getInitialState().getIndex();

		stateSet.add(initStateIdxArray);
		
		// Local state stack used to process each state found in a DFS manner, starting from the initial state
		Stack<State[]> stateStack = new Stack<State[]>();
		State[] initStateArray = new State[2];
		State initState1 = sg1.getInitialState();
		State initState2 = sg2.getInitialState();
		initStateArray[0] = initState1;
		initStateArray[1] = initState2;
		stateStack.push(initStateArray);
		
		HashSet<Pair<State, State>> syncStateTbl = new HashSet<Pair<State, State>>();
		syncStateTbl.add(new Pair<State, State>(initState1, initState2));

		/*
		 * For each local state pair, fire the enabled transitions until no more new local state pairs are found.
		 */
		int iterations = 0;
		while(stateStack.empty()==false){
			iterations++;
			
			State[] curStateArray = stateStack.pop(); 
			State state1 = curStateArray[0];
			State state2 = curStateArray[1];
			//lpn1 = state1.getLpn();
			//lpn2 = state2.getLpn();
						
//			if(!compatibleStates(state1, state2)){
//				System.out.println("      ERROR: mismatching global vars");
//			}
			
			// List of enabled transitions that modifies the other module
			List<LPNTran> modify = new LinkedList<LPNTran>();
			
			// List of enabled input transitions that need to be synchronized
			List<StateTran> synch = new LinkedList<StateTran>();
			
			// List of enabled transitions that should be independently fired by module 1
			List<LPNTran> indep1 = new LinkedList<LPNTran>();
			
			// List of enabled transitions that should be independently fired by module 2
			List<LPNTran> indep2 = new LinkedList<LPNTran>();
			
			// Set of enabled transitions
			List<LPNTran> enabled1 = sg1.getEnabled(state1, false).clone();
			List<LPNTran> enabled2 = sg2.getEnabled(state2, false).clone();
			
			// Set of state transitions from external LPN transitions
			List<StateTran> ext1 = sg1.externalStateTranMap.get(state1);
			List<StateTran> ext2 = sg2.externalStateTranMap.get(state2);
			
			
			/*
			 * Determine if the enabled LPNTran should be fired independently or if it modifies module 2
			 */
			for(LPNTran enabledTran : enabled1){
				if(modifyingTrans.contains(enabledTran)){
					modify.add(enabledTran);
				}
				else{
					indep1.add(enabledTran);
				}
			}
			
			/*
			 * Determine if the enabled LPNTran should be fired independently or if it modifies module 1
			 */
			for(LPNTran enabledTran : enabled2){
				if(modifyingTrans.contains(enabledTran)){
					modify.add(enabledTran);
				}
				else{
					indep2.add(enabledTran);
				}
			}
			
			/*
			 * Go through the externally enabled LPNTrans from module 1 to find which need to be synchronized, 
			 * or if the should be fired independently.  Synchronize LPNTran if both modules have the input
			 * transition enabled, otherwise skip.
			 */
			if(ext1 != null){
				for(StateTran stateTran : ext1){
					LPNTran enabledTran = stateTran.getLpnTran();
					
					if(synchronousTrans.contains(enabledTran)){
						boolean enabled = false;
						if(ext2 != null){
							for(StateTran stTran : ext2){
								if(stTran.getLpnTran() == enabledTran){
									enabled = true;
									break;
								}
							}
						}
						
						if(enabled){
							synch.add(stateTran);
						}
					}
					else if(!modifyingTrans.contains(enabledTran)){
						indep1.add(enabledTran);
					}
				}
			}
			
			/*
			 * Go through the externally enabled LPNTrans from module 2 to find which need to be synchronized, 
			 * or if the should be fired independently.  Synchronize LPNTran if both modules have the input
			 * transition enabled, otherwise skip.
			 */
			if(ext2 != null){
				for(StateTran stateTran : ext2){
					LPNTran enabledTran = stateTran.getLpnTran();
					
					if(synchronousTrans.contains(enabledTran)){
						boolean enabled = false;
						if(ext1 != null){
							for(StateTran stTran : ext1){
								if(stTran.getLpnTran() == enabledTran){
									enabled = true;
									break;
								}
							}
						}
						
						if(enabled){
							synch.add(stateTran);
						}
					}
					else if(!modifyingTrans.contains(enabledTran)){
						indep2.add(enabledTran);
					}
				}
			}
			
			
			/*
			 * Fire the set of enabled transitions that modifies the other module
			 */
			for(LPNTran enTran : modify){
				State nextState1 = null;
				State nextState2 = null;
				
				if (enTran.getLpn() == lpn1) { //state1.getLpn()){ // LPNTran from module 1 modifies module 2
					nextState1 = enTran.constrFire(state1);
					
					// Indices of common variables
					Pair<int[], int[]> indexPair = getInterfacePair(enTran.getLpn(), lpn2); //state2.getLpn());
					int[] tranIndices = indexPair.getLeft();
					int[] stateIndices = indexPair.getRight();
					
					// Create the next state start point
					nextState2 = new State(state2);
					int[] nextVector = nextState2.getVector();
					
					// Find the indices of the modified variables
					Set<VarNode> assignedNodeSet = enTran.getAssignedVars();
					List<Integer> assignedIndexList = new ArrayList<Integer>(assignedNodeSet.size());
					for(VarNode n : assignedNodeSet){
						assignedIndexList.add(n.getIndex(state1.getVector()));
					}
					
					// Update the variables shared by the two modules
					int[] nextState1Vector = nextState1.getVector();
					for(int i = 0; i < tranIndices.length; i++){
						int tranIndex = tranIndices[i];
						int stateIndex = stateIndices[i];
						
						if(assignedIndexList.contains(tranIndex)){
							nextVector[stateIndex] = nextState1Vector[tranIndex];
						}
					}
				}
				else{ // LPNTran from module 2 modifies module 1
					nextState2 = enTran.constrFire(state2);
					
					// Indices of common variables
					Pair<int[], int[]> indexPair = getInterfacePair(enTran.getLpn(), lpn1); //state1.getLpn());
					int[] tranIndices = indexPair.getLeft();
					int[] stateIndices = indexPair.getRight();
					
					// Create the next state start point
					nextState1 = new State(state1);
					int[] nextVector = nextState1.getVector();
					
					// Find the indices of the modified variables
					Set<VarNode> assignedNodeSet = enTran.getAssignedVars();
					List<Integer> assignedIndexList = new ArrayList<Integer>(assignedNodeSet.size());
					for(VarNode n : assignedNodeSet){
						assignedIndexList.add(n.getIndex(state2.getVector()));
					}

					// Update the variables shared by the two modules
					int[] nextState2Vector = nextState2.getVector();
					for(int i = 0; i < tranIndices.length; i++){
						int tranIndex = tranIndices[i];
						int stateIndex = stateIndices[i];
						
						if(assignedIndexList.contains(tranIndex)){
							nextVector[stateIndex] = nextState2Vector[tranIndex];
						}
					}
				}
				
				/*
				 * Add the next states and state transitions to each module's state graph
				 */
				nextState1 = sg1.addState(nextState1);
				nextState2 = sg2.addState(nextState2);
				sg1.addStateTranCompositional(state1, enTran, nextState1);
				sg2.addStateTranCompositional(state2, enTran, nextState2);
				
				/*
				 * If the next local state pair is new, add to the state set and push onto stack
				 */
				Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
				if (syncStateTbl.contains(syncState) == false) {
					syncStateTbl.add(syncState);
					State[] nextStateArray = new State[2];
					nextStateArray[0] = nextState1;
					nextStateArray[1] = nextState2;
					stateStack.push(nextStateArray);
//					System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
				}
			}
			
			/*
			 * Fire the set of enabled transitions local to module 1
			 */
			for(LPNTran enTran : indep1){
				if(enTran.getLpn() != lpn1){ // Externally fired LPNTran
					List<StateTran> extList = sg1.externalStateTranMap.get(state1);
					for(StateTran tran : extList){
						if(tran.getLpnTran() != enTran){
							continue;
						}
						
						State nextState1 = tran.getNextState();
						State nextState2 = state2;
						
						nextState1 = sg1.addState(nextState1);
						sg1.addStateTranCompositional(state1, enTran, nextState1);
						
						Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
						if (syncStateTbl.contains(syncState) == false) {
							syncStateTbl.add(syncState);
							State[] nextStateArray = new State[2];
							nextStateArray[0] = nextState1;
							nextStateArray[1] = nextState2;
							stateStack.push(nextStateArray);
//							System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
						}
					}
				}
				else{ // Locally fired LPNTran
					State nextState1 = enTran.constrFire(state1);
					State nextState2 = state2;
					
					nextState1 = sg1.addState(nextState1);
					sg1.addStateTranCompositional(state1, enTran, nextState1);
					
					Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
					if (syncStateTbl.contains(syncState) == false) {
						syncStateTbl.add(syncState);
						State[] nextStateArray = new State[2];
						nextStateArray[0] = nextState1;
						nextStateArray[1] = nextState2;
						stateStack.push(nextStateArray);
//						System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
					}
				}
			}
			
			/*
			 * Fire the set of enabled transitions local to module 2
			 */
			for(LPNTran enTran : indep2){
				if(enTran.getLpn() != lpn2){ // Externally fired LPNTran
					List<StateTran> extList = sg2.externalStateTranMap.get(state2);
					for(StateTran tran : extList){
						if(tran.getLpnTran() != enTran){
							continue;
						}
						
						State nextState1 = state1;
						State nextState2 = tran.getNextState();
						
						nextState2 = sg2.addState(nextState2);
						sg2.addStateTranCompositional(state2, enTran, nextState2);
						
						Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
						if (syncStateTbl.contains(syncState) == false) {
							syncStateTbl.add(syncState);
							State[] nextStateArray = new State[2];
							nextStateArray[0] = nextState1;
							nextStateArray[1] = nextState2;
							stateStack.push(nextStateArray);
//							System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
						}
					}
				}
				else{ // Locally fired LPNTran
					State nextState2 = enTran.constrFire(state2);
					State nextState1 = state1;
					
					nextState2 = sg2.addState(nextState2);
					sg2.addStateTranCompositional(state2, enTran, nextState2);

					Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
					if (syncStateTbl.contains(syncState) == false) {
						syncStateTbl.add(syncState);
						State[] nextStateArray = new State[2];
						nextStateArray[0] = nextState1;
						nextStateArray[1] = nextState2;
						stateStack.push(nextStateArray);
//						System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
					}
				}
			}
			
			/*
			 * Fire the set of enabled input transitions that need to be synchronized between both modules
			 */
			for(StateTran enStateTran : synch){
				LPNTran enTran = enStateTran.getLpnTran();
				
				if(enStateTran.getCurrentState().getLpn() == lpn1) { //sg1.getLpn()){
					State nextState1 = enStateTran.getNextState();
					
					List<StateTran> extList = sg2.externalStateTranMap.get(state2);
					for(StateTran tran : extList){
						if(tran.getLpnTran() != enTran){
							continue;
						}
						
						State nextState2 = tran.getNextState();
						
						if(compatibleStates(nextState1, nextState2)){
							Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
							if (syncStateTbl.contains(syncState) == false) {
								syncStateTbl.add(syncState);
								State[] nextStateArray = new State[2];
								nextStateArray[0] = nextState1;
								nextStateArray[1] = nextState2;
								stateStack.push(nextStateArray);
//								System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
							}
						}
						else{
//							System.out.println("!compatible");
//							if(!compatibleStates(state1, state2))
//								System.out.println("    NON COMPATIBLE CURRENT STATES");
						}
					}	
				}
				else{
					State nextState2 = enStateTran.getNextState();
					
					List<StateTran> extList = sg1.externalStateTranMap.get(state1);
					for(StateTran tran : extList){
						if(tran.getLpnTran() != enTran){
							continue;
						}
						
						State nextState1 = tran.getNextState();
						
						if(compatibleStates(nextState1, nextState2)){
							Pair<State, State> syncState = new Pair<State, State>(nextState1, nextState2);
							if (syncStateTbl.contains(syncState) == false) {
								syncStateTbl.add(syncState);
								State[] nextStateArray = new State[2];
								nextStateArray[0] = nextState1;
								nextStateArray[1] = nextState2;
								stateStack.push(nextStateArray);
//								System.out.println("      (" + state1.getIndex() + ", " + state2.getIndex() + ") -> (" + nextState1.getIndex() + ", " + nextState2.getIndex() + ")");
							}
						}
						else{
//							System.out.println("!compatible");
//							if(!compatibleStates(state1, state2))
//								System.out.println("    NON COMPATIBLE CURRENT STATES");
						}
					}
				}
			}
		}
		
		if(Options.getVerbosity() > 20){
			System.out.println("      " + sg1.getLpn().getLabel() + " #states: " + sg1.reachSize());
			System.out.println("      " + sg2.getLpn().getLabel() + " #states: " + sg2.reachSize());
			System.out.println("      " + sg1.getLpn().getLabel() + " #trans: " + sg1.numTransitions());
			System.out.println("      " + sg2.getLpn().getLabel() + " #trans: " + sg2.numTransitions());
			
			long elapsedTimeMillis = System.currentTimeMillis()-startTime; 
			float elapsedTimeSec = elapsedTimeMillis/1000F;
			System.out.println("      Elapsed time: " + elapsedTimeSec + " sec");
		}
		
		int diff = sg1.numTransitions() - numStateTransBefore1;
		diff += sg2.numTransitions() - numStateTransBefore2;
		
		return diff;
	}
	
	/**
	 * Compares the shared global variables of the two given states
	 * @param state1 - First state to compare
	 * @param state2 - Second state to compare
	 * @return Returns TRUE if the state vectors contain the same value for each shared variable
	 */
	private static boolean compatibleStates(State state1, State state2){
		LPN lpn1 = state1.getLpn();
		LPN lpn2 = state2.getLpn();
		
		if(lpn1 == lpn2){
			return false;
		}
		
		Pair<int[], int[]> commonIndexPair = getInterfacePair(lpn1, lpn2);
		int[] indices1 = commonIndexPair.getLeft();
		int[] indices2 = commonIndexPair.getRight();
		
		int[] vector1 = state1.getVector();
		int[] vector2 = state2.getVector();
		
		for(int i = 0; i < indices1.length; i++){
			int index1 = indices1[i];
			int index2 = indices2[i];
			
			int val1 = vector1[index1];
			int val2 = vector2[index2];
			
			if(val1 != val2){
				return false;
			}
		}
		
		return true;
	}
	
	/**
	 * Performs the new CRA approaching using local search.
	 * @param designUnitSet - Set of state graphs to construct
	 */
	public static void craLocalSearch(StateGraph[] designUnitSet){
		String info = "--> start CRA with local search ...";
		Console.print(info, Console.MessageType.dynamicInfo, 1);
		
		//* Initialize and start monitor to record runtime information.
		RuntimeMonitor monitor = new RuntimeMonitor();
		Thread t = new Thread(monitor);
		t.start();
		
		/*
		 * Initializes the private static attributes used for compositional reachability analysis
		 */
		setupAttributes(designUnitSet);
		
		// Maps a SG to a list of other SGs that modify its inputs
		HashMap<StateGraph, List<StateGraph>> inputSrcMap = new HashMap<StateGraph, List<StateGraph>>();
		
		// Timing and memory usage info
		long startTime = System.currentTimeMillis();
		long peakMemUsed = 0;
		long peakMemTotal = 0;
		
		/*
		 * Initialize each module's state graph.
		 * Add the initial state, find input sources and add to inpuSrcMap, 
		 * and also find the common interface between every pair of state graphs.
		 */
		for (StateGraph sg : designUnitSet) {
			LPN lpn = sg.getLpn();
			
            // Add initial state to state graph
			State init = lpn.getInitState();
			init = sg.addState(init);
			sg.setInitialState(init);
			addFrontierState(sg, init);
            
			// List of LPNs that modify the current LPN
			List<StateGraph> srcArray = new ArrayList<StateGraph>();

			// determine the LPNs that modify the current LPN
			for(StateGraph sg2 : designUnitSet){
				if(sg == sg2){
					continue;
				}
				
				LPN lpn2 = sg2.getLpn();
				if(lpn2.modifies(lpn)){
					if(inputSrcMap.containsKey(sg2)){
						if(!inputSrcMap.get(sg2).contains(sg)){
							srcArray.add(sg2);
						}
					}
					else{
						srcArray.add(sg2);
					}
				}
				
				// Find the common interface
				Pair<int[], int[]> indexPair = lpn2.constrInterfacePair(lpn);
				setInterfacePair(lpn2, lpn, indexPair);
			}

			inputSrcMap.put(sg, srcArray);
		}
		
		// Keeps track of the number of new state transitions generated during an iteration
		int numNewStateTrans = 1;
		
		// Keeps track of the current iteration
		int iter = 0;
		while(numNewStateTrans > 0){
			numNewStateTrans = 0;
			iter++;
			
			/*
			 * Iterate through the design unit set
			 */
			for(StateGraph m1 : designUnitSet){

				/*
				 * Go through each module that modifies the current module
				 */
				for(StateGraph m2 : inputSrcMap.get(m1)){
					String lsinfo = "--> searching " + m1.getLpn().getLabel() + " and " + m2.getLpn().getLabel() + "\n";
					lsinfo += "\t" + m1.getLabel() + ": (" + m1.reachSize() + ", " + m1.numTransitions() + ")\t"+ m2.getLabel() + ": (" + m2.reachSize() + ", " + m2.numTransitions() +");";
					Console.print(lsinfo, Console.MessageType.dynamicInfo, 1);
					numNewStateTrans += localSearch(m1, m2);
//					System.out.println(numNewStateTrans);
				}
			}
		}
		
		int numStates = 0;
		int numTrans = 0;
		int numConstr = 0;
		String finalStats = "";
		for (StateGraph sg : designUnitSet) {
			updateConstraints(sg);
			updateStateFrontier(sg);
			
			finalStats += "\t" + sg.getLabel() + ": (" + sg.reachSize() + ", " + sg.numTransitions() + ")\n";
			
			numStates += sg.reachSize();
			numTrans += sg.numTransitions();
			numConstr += numConstraints(sg);
		}
		
		if(Options.getVerbosity() > 0) {			
			System.out.print("\n--> Local SG info:\n" + finalStats);
			System.out.println("--> Peak used memory: " + monitor.getPeakUsedMem() + " MB");
			System.out.println("--> Peak total memory: " + monitor.getPeakTotalMem() + " MB");
			System.out.println("--> Final used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB");
			
			long elapsedTimeMillis = System.currentTimeMillis()-startTime; 
			float elapsedTimeSec = elapsedTimeMillis/1000F;
			System.out.println("--> Elapsed time: " + elapsedTimeSec + " sec");
			
			if(elapsedTimeSec > 60){
				float elapsedTime = elapsedTimeSec/(float)60;
				System.out.println("   --> Elapsed time: " + elapsedTime + " min");
			}
		}
		
		//* Stop and kill the thread for monitor.
		t.interrupt();
	}
	
	/*
	 * A version of search_dfs() without storing the global states of the whole design under consideratin.
	 * However, a global stack is manipulated during the search.
	 * It only produces a subset of states compared against the search_dfs()
	 */
	public static HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> searchDfsUnderApprox(StateGraph[] sgArray, 
			IndexObjMap<IntArrayObj> globalVecTbl, DualHashMap<String, Integer> gVarIndexMap, HashMap<String, Integer> initGlobalVecMap) {
		int iterations = 0;
		int max_stack_depth = 0;
		double peakUsedMem = 0;
		double peakTotalMem = 0;
		boolean failure = false;
		int tranFiringCnt = 0;
		int totalStateCnt = 1;
		int arraySize = sgArray.length;
		boolean noGlobals = true;

		/* The vector of the value of all global variables and outputs from all modules in the current state  */
		IntArrayObj curGlobalVecObj = null;
		IntArrayObj nextGlobalVecObj = null;

//		IndexObjMap<IntArrayObj> globalVecTbl = new IndexObjMap<IntArrayObj>();
		HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> globalTranTbl = new HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>>();

		Stack<State> stateStack = new Stack<State>();
		Stack<IntArrayObj> gVecStack = new Stack<IntArrayObj>();
		Stack<LpnTranList> lpnTranStack = new Stack<LpnTranList>();

		LinkedList<LPNTran> traceCex = new LinkedList<LPNTran>();

		if(gVarIndexMap.size() > 0)
			noGlobals = false;
		
		State[] initStateArray = new State[sgArray.length];
		for(int i = 0; i < initStateArray.length; i++){
			initStateArray[i] = sgArray[i].getInitialState();
		}
		
		State[] curStateArray = initStateArray;
		int[] curGlobalVec = makeVec(initGlobalVecMap, gVarIndexMap);
		curGlobalVecObj = globalVecTbl.add(new IntArrayObj(curGlobalVec));
		LpnTranList curEnabled = sgArray[0].getEnabled(initStateArray[0], curGlobalVecObj.toArray(), gVarIndexMap, curGlobalVecObj.getIndex()).clone();		
		int activeIdx = 0;
		boolean Done = false;

		/*  Main search loop */
		main_while_loop: while (failure == false && Done == false) {
			long curTotalMem = Runtime.getRuntime().totalMemory();
			long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

			if (curTotalMem > peakTotalMem)
				peakTotalMem = curTotalMem;

			if (curUsedMem > peakUsedMem)
				peakUsedMem = curUsedMem;

			if (stateStack.size() > max_stack_depth)
				max_stack_depth = stateStack.size();


			iterations++;

//			if(iterations % this.StatusUpdatePeriodShort == 0) {
//				long elapsedTimeMillis = System.currentTimeMillis() - localStartTime; 
//				float elapsedTimeSec = elapsedTimeMillis/1000F;
//				if(elapsedTimeSec >= Options.TimeUpperBound) {
//					System.out.println("Elapsed time: " + elapsedTimeSec + " exceeds the limit " + Options.getTimeUpperBound() +".");
//					System.out.println("Terminate the search!");
//					break main_while_loop;	
//				}
//				if(peakUsedMem/1000000 >= Options.MemUpperBound) {
//					System.out.println("Used memory: " + peakUsedMem/1000000 + " exceeds the limit " + Options.getMemUpperBound() +".");
//					System.out.println("Terminate the search!");
//					break main_while_loop;
//				}
//			}

//			if (iterations % this.StatusUpdatePeriodLong == 0) {
//				String statusUpdate = "---> "
//						+ "# LPN transition firings: " + tranFiringCnt
//						+ ", # of prjStates found: " + totalStateCnt
//						+ ", stack_depth: " + stateStack.size()
//						//+ ", MDD node count: " + mddMgr.nodeCnt()
//						+ " used memory: " + (float) curUsedMem / 1000000
//						+ " free memory: "
//						+ (float) Runtime.getRuntime().freeMemory() / 1000000;
//
//				String statsLevel5 = "---> " + " # of global vectors: " + globalVecTbl.size() + "\n";
//
//				if(FrontEnd.GUILoaded)
//				{
//					if(Options.getVerbosity() > 0)
//						Console.addInformation(statusUpdate, ConsoleEvent.eventType.textConsole);
//
//					if(Options.getVerbosity() > 5)
//						Console.addInformation(statsLevel5, ConsoleEvent.eventType.textConsole);
//				}
//
//				else
//				{
//					if(Options.getVerbosity() > 0)
//						System.out.println(statusUpdate);
//
//					if(Options.getVerbosity() > 5)
//						System.out.println(statsLevel5);
//				}
//			}

			// If all enabled transitions of the current LPN are considered,
			// then consider the next LPN
			// by increasing the curIndex.
			// Otherwise, if all enabled transitions of all LPNs are considered,
			// then pop the stacks.

			if (curEnabled.size() == 0) {				
				activeIdx++;
				while (activeIdx < arraySize) {
					curEnabled = (sgArray[activeIdx].getEnabled(curStateArray[activeIdx], curGlobalVecObj.toArray(), gVarIndexMap, curGlobalVecObj.getIndex())).clone();
					if (curEnabled.size() > 0) {
						break;
					} 					
					activeIdx++;
				}
			}

			if (activeIdx == arraySize) {
				if(stateStack.size() == 0) {
					Done=true;
					break main_while_loop;
				}
				State curState = stateStack.pop();
				activeIdx = curState.getLpn().getIndex();
				curStateArray[activeIdx] = curState;
				curGlobalVecObj = gVecStack.pop();
				curEnabled = lpnTranStack.pop();
				if(traceCex.size()>0){
					traceCex.removeLast();		
				}
				
				continue;
			}

			State curState = curStateArray[activeIdx];
			LPNTran firedTran = curEnabled.removeLast();
			HashMap<String, Integer> NewGlobalVecMap = new HashMap<String, Integer>();
			Pair<State, HashMap<String,Integer>> nextModState = firedTran.fire(sgArray[activeIdx], curState, curGlobalVecObj.toArray(), curGlobalVecObj.getIndex(), gVarIndexMap);
			State nextState = nextModState.getLeft();
			NewGlobalVecMap = nextModState.getRight();
			traceCex.addLast(firedTran);
			tranFiringCnt++;

			nextGlobalVecObj = curGlobalVecObj;
			if(NewGlobalVecMap.size() != 0) {
				curGlobalVec = curGlobalVecObj.toArray();
				int[] nextGlobalVec = Arrays.copyOf(curGlobalVec, curGlobalVec.length);
				Set<String> vars = NewGlobalVecMap.keySet();
				for(String var : vars) 
					nextGlobalVec[gVarIndexMap.getValue(var)] = NewGlobalVecMap.get(var);
				nextGlobalVecObj = new IntArrayObj(nextGlobalVec);
			}


			boolean existing = true;
			if(noGlobals==false) {
				IntArrayObj tmp = globalVecTbl.get(nextGlobalVecObj);
				if(tmp==null) {
					existing = false; 
					nextGlobalVecObj = globalVecTbl.add(nextGlobalVecObj);
				}
				else
					nextGlobalVecObj = tmp;
				if(curGlobalVecObj != nextGlobalVecObj || firedTran.local()==false) {
					HashMap<LPNTran, HashSet<IntArrayObj>> gNextTranTbl = globalTranTbl.get(curGlobalVecObj);
					if(gNextTranTbl == null) {
						existing = false;
						gNextTranTbl = new HashMap<LPNTran, HashSet<IntArrayObj>>();
						globalTranTbl.put(curGlobalVecObj, gNextTranTbl);
					}
					HashSet<IntArrayObj> nextGVecSet = gNextTranTbl.get(firedTran);
					if(nextGVecSet == null) {
						existing = false;
						nextGVecSet = new HashSet<IntArrayObj>();
						gNextTranTbl.put(firedTran, nextGVecSet);
					}
					if(nextGVecSet.contains(nextGlobalVecObj) == false) {
						existing = false;
						nextGVecSet.add(nextGlobalVecObj);
					}
				}
			}

			// Check if the firedTran causes disabling error or deadlock.
			LinkedList<LPNTran>[] curEnabledArray = new LinkedList[arraySize];
			LinkedList<LPNTran>[] nextEnabledArray = new LinkedList[arraySize];
			for (int i = 0; i < arraySize; i++) {
				StateGraph lpn_tmp = sgArray[i];
				if(i == activeIdx)
					curStateArray[activeIdx] = nextState;

				LinkedList<LPNTran> enabledList = lpn_tmp.getEnabled(curStateArray[i], nextGlobalVecObj.toArray(), gVarIndexMap, nextGlobalVecObj.getIndex());
				nextEnabledArray[i] = enabledList;

				if(i == activeIdx)
					curStateArray[activeIdx] = curState;


				if(Options.checkDisablingError()==true) {
					enabledList = lpn_tmp.getEnabled(curStateArray[i], curGlobalVecObj.toArray(), gVarIndexMap, curGlobalVecObj.getIndex());
					curEnabledArray[i] = enabledList;

					LPNTran disabledTran = firedTran.disablingError1(curEnabledArray[i], nextEnabledArray[i]);
					if (disabledTran != null) {
						System.err.println("Disabling Error: " + disabledTran.getFullLabel() + " is disabled by " + firedTran.getFullLabel());
						for (int curIdx = 0; curIdx < arraySize; curIdx++) {
							System.out.println(curStateArray[curIdx].toString());
						}
						System.out.println(">>>>>");
						for (int nxtIdx = 0; nxtIdx < arraySize; nxtIdx++) {
							System.out.println(curStateArray[nxtIdx].toString());
						}
						System.out.println("\n\n");
						failure = true;
						break main_while_loop;
					}
				}
			}

//			if(existing==false) {
//				nextActiveIdx = Analysis.deadLock(sgArray, nextEnabledArray) ;
//
//				if(nextActiveIdx == arraySize) {
//					String statusUpdate = "*** Verification failed: deadlock.\n" 
//										+ "*** Deadlock state layout:\n";
//					for (int i = 0; i < arraySize; i++) {
//						statusUpdate += curStateArray[i].toString();
//					}
//
//					statusUpdate += this.printGlobalVec(nextGlobalVecObj.toArray(), gVarIndexMap);
//
//					if(FrontEnd.GUILoaded)	{
//						Console.addInformation(statusUpdate, ConsoleEvent.eventType.textConsole);
//					}
//					else {
//						System.out.println(statusUpdate);
//					}
//					
//					failure = true;
//					break main_while_loop;
//				}
//			}

			int nextActiveIdx = 0;
			
			if (existing == false) {	
				stateStack.push(curState);
				if(noGlobals==false) {
					gVecStack.push(curGlobalVecObj);
				}
				else {
					gVecStack.push(null);
				}
				lpnTranStack.push(curEnabled);

				curStateArray[activeIdx] = nextState;
				curGlobalVecObj = nextGlobalVecObj;
				activeIdx = nextActiveIdx;
				curEnabled = (LpnTranList)nextEnabledArray[activeIdx].clone();
				totalStateCnt++;
			}
			else {
				traceCex.removeLast();				
			}
		}

		return globalTranTbl;
	}
	
	private static int[] makeVec(HashMap<String,Integer> gVecMap, DualHashMap<String, Integer> gVarIndexMap) {
		int[] gVec = new int[gVarIndexMap.size()];

		for(int i = 0; i < gVarIndexMap.size(); i++) {
			String var = gVarIndexMap.getKey(i);
			Integer val = gVecMap.get(var);
			gVec[i] = val;
		}
		
		return gVec;
	}
	
	private static String printGlobalVec(int[] gVec, DualHashMap<String, Integer> gVarIndexMap) {
		if(gVec==null)
			return "[]";

		String result = "[";
		for(int i = 0; i < gVec.length; i++) {
			result += "(" + gVarIndexMap.getKey(i) + "," + gVec[i] + "), ";
		}
		result += "]\n";

		return result;
	}
	
	/**
	 * Finds as many global state vectors as possible for the specified state graph.
	 * @param sg - State graph to perform global search on
	 * @param globalVecTbl - Set of global state vector
	 * @param globalTranTbl - Set of global state transitions
	 */
	public static void globalSearch1(
			StateGraph sg, 
			IndexObjMap<IntArrayObj> globalVecTbl, 
			HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> globalTranTbl, 
			DualHashMap<String, Integer> gVarIndexMap,
			HashMap<String, Integer> initGlobalVecMap){

		Stack<State> localStateStack = new Stack<State>();
		Stack<IntArrayObj> globalStateStack = new Stack<IntArrayObj>();
		HashMap<State, HashSet<IntArrayObj>> stateMap = new HashMap<State, HashSet<IntArrayObj>>();
		
		int[] initGlobalVec = makeVec(initGlobalVecMap, gVarIndexMap);
		IntArrayObj initGlobalVecObj = globalVecTbl.add(new IntArrayObj(initGlobalVec));	
		
		// add initial local and global state to their stacks and state sets
		localStateStack.push(sg.getInitialState());
		globalStateStack.push(initGlobalVecObj);
		addCompositeState(stateMap, sg.getInitialState(), initGlobalVecObj);
		
//		System.out.println(sg.getLpn().getLabel() + ": ");
//		System.out.println("   init global state: " + Arrays.toString(initGlobalVec));
		
		while(!localStateStack.empty()){
			State currentLocalState = localStateStack.pop();
			IntArrayObj currentGlobalState = globalStateStack.pop();
			int[] currentGlobalVec = currentGlobalState.toArray();
			
			// Get the enabled LPNTrans
			List<LPNTran> enabledTrans = sg.getEnabled(currentLocalState, currentGlobalState.toArray(), gVarIndexMap, currentGlobalState.getIndex());
			
			// Get enabled global transitions
			HashMap<LPNTran, HashSet<IntArrayObj>> inputTrans = new HashMap<LPNTran, HashSet<IntArrayObj>>();
			HashMap<LPNTran, HashSet<IntArrayObj>> tmpMap = globalTranTbl.get(currentGlobalState);
			
			// Remove output transitions
			if(tmpMap != null){
				List<LPNTran> removeList = new LinkedList<LPNTran>();
				inputTrans.putAll(tmpMap);
				for(LPNTran lpnTran : inputTrans.keySet()){
					if(lpnTran.getLpn() == sg.getLpn()){
						removeList.add(lpnTran);
					}
				}
				
				for(LPNTran lpnTran: removeList){
					inputTrans.remove(lpnTran);
				}
			}
			
//			System.out.println("      CURRENT STATE: " + Arrays.toString(currentGlobalState.toArray()));
//			System.out.println("         enabledTrans: " + enabledTrans);
//			System.out.println("         inputTrans: " + inputTrans);
			
			/* For each local enabled LPNTran fire and add new local or global state to set. 
			 * Also check for new global state transitions. 
			 * If the next local or global state has not been encountered before, add both to the stack.
			 */
			for(LPNTran lpnTran : enabledTrans){
				Pair<State, HashMap<String,Integer>> nextModState = null;
				nextModState = lpnTran.fire(sg, currentLocalState, currentGlobalState.toArray(), currentGlobalState.getIndex(), gVarIndexMap);
				
				State nextState = nextModState.getLeft();
				HashMap<String, Integer> NewGlobalVecMap = nextModState.getRight();

				// Check if any global variables were modified
				if(NewGlobalVecMap.size() != 0) {
					
					/*
					 * Create the next state's global vector with the set of modified global varaibles
					 */
					int[] nextGlobalVec = Arrays.copyOf(currentGlobalVec, currentGlobalVec.length);
					Set<String> vars = NewGlobalVecMap.keySet();
					for(String var : vars) {
						nextGlobalVec[gVarIndexMap.getValue(var)] = NewGlobalVecMap.get(var);
					}
					
					// Next global state
					IntArrayObj nextGlobalState = new IntArrayObj(nextGlobalVec);
					
//					System.out.println("            LPNTran: " + lpnTran.getFullLabel());
//					System.out.println("            nextState: " + Arrays.toString(nextGlobalState.toArray()));
					
					// Add next global state to the table of global states
					IntArrayObj ret = globalVecTbl.add(nextGlobalState);
					
					// Check if the next global state is new
					if(ret == nextGlobalState){
						
						// Add global state transition
						addGlobalTran(currentGlobalState, nextGlobalState, lpnTran, globalTranTbl);
						//addStateTran(sg, currentLocalState, currentGlobalState, nextState, nextGlobalState, lpnTran, gVarIndexMap);
						
						// Add next states to the state map
						addCompositeState(stateMap, nextState, nextGlobalState);
						
						// Add next states to the stack
						globalStateStack.push(nextGlobalState);
						localStateStack.push(nextState);
					}
					else{
						nextGlobalState = ret;
						
						// Add global state transition
						addGlobalTran(currentGlobalState, nextGlobalState, lpnTran, globalTranTbl);
						
						/*
						 * If the combination of the local and global state has not been encountered before, 
						 * add states to stack
						 */
						if(addCompositeState(stateMap, nextState, nextGlobalState)){
							localStateStack.push(nextState);
							globalStateStack.push(nextGlobalState);
						}
						
						//addStateTran(sg, currentLocalState, currentGlobalState, nextState, nextGlobalState, lpnTran, gVarIndexMap);
					}
				}
				else{
					/*
					 * If the combination of the local and global state has not been encountered before, 
					 * add states to stack
					 */
					if(addCompositeState(stateMap, nextState, currentGlobalState)){
						localStateStack.push(nextState);
						globalStateStack.push(currentGlobalState);
					}
					
					//addStateTran(sg, currentLocalState, currentGlobalState, nextState, currentGlobalState, lpnTran, gVarIndexMap);
				}
			}
			
			/* For each input enabled LPNTran fire from current state.
			 */
			for(Entry<LPNTran, HashSet<IntArrayObj>> e : inputTrans.entrySet()){
				for(IntArrayObj o : e.getValue()){
//					System.out.println("   " + e.getKey().getFullLabel() + " - " + Arrays.toString(o.toArray()));
					
					/*
					 * If the combination of the local and global state has not been encountered before, 
					 * add states to stack
					 */
					if(addCompositeState(stateMap, currentLocalState, o)){
						localStateStack.push(currentLocalState);
						globalStateStack.push(o);
					}
					
					//addStateTran(sg, currentLocalState, currentGlobalState, currentLocalState, o, e.getKey(), gVarIndexMap);
				}
			}
		}
	}
	
	private static int currentTime = 0;
	
	/**
	 * Finds as many global state vectors as possible for the specified state graph.
	 * @param sg - State graph to perform global search on
	 * @param globalVecTbl - Set of global state vector
	 * @param globalTranTbl - Set of global state transitions
	 */
	public static void globalSearch(StateGraph sg, CompositeStateGraph globalStateGraph, 
			IndexObjMap<IntArrayObj> globalVecTbl,
			DualHashMap<String, Integer> gVarIndexMap,
			HashMap<String, Integer> initGlobalVecMap){

		Stack<CompositeState> globalStateStack = new Stack<CompositeState>();		
//		IndexObjMap<State> moduleStateCache = new IndexObjMap<State>();
		
		// add initial global state to the stack
		globalStateStack.push(globalStateGraph.getInitState());
		sg.addState(sg.getInitialState());
//		moduleStateCache.add(sg.getInitialState());
		
		//TODO: keep module states locally
		
		LPN lpn = sg.getLpn();
		
//		System.out.println("MODULE: " + sg.getLpn().getLabel() + ": ");
//		System.out.println("   init global state: " + Arrays.toString(initGlobalVec));
		
		while(!globalStateStack.empty()){
			CompositeState currentGlobalState = globalStateStack.pop();
			currentGlobalState.time2 = currentTime;
			
//			System.out.println("   CURRENT STATE: " + currentGlobalState.getIndex());
			
			// Check if this state contains fired transitions from this module
			List<CompositeStateTran> firedTrans = currentGlobalState.getOutgoingStateTranList();
			
			boolean alreadyFired = false;
			for(CompositeStateTran stateTran : firedTrans){
				LPNTran lpnTran = stateTran.getLPNTran();
				
				if(lpnTran.getLpn() == lpn){
					alreadyFired = true;
					break;
				}
			}
			
			// if this state has fired transitions from this module, then go to the next state, if
			// not already processed in this function call, by adding to the stack
			if(alreadyFired){
//				System.out.println("      ALREADY FIRED...");
				for(CompositeStateTran stateTran : firedTrans){
					CompositeState nextState = stateTran.getNextState();
					
					if(nextState.time2 != currentTime){
						globalStateStack.push(nextState);
					}
				}
				
				continue;
			}
			
			for(CompositeStateTran stateTran : firedTrans){
				CompositeState nextState = stateTran.getNextState();

				if(nextState.time2 != currentTime){
					globalStateStack.push(nextState);
				}
			}
			
			// if this state does not have any fired transitions from this module
			// Get the set of enabled transitions and fire them
			// for each new next state add to the stack
			int[] currentStateTuple = currentGlobalState.getStateTuple();
			int moduleStateIndex = currentStateTuple[lpn.getIndex()];
			State moduleState = sg.getState(moduleStateIndex);
			
			int globalVecIndex = currentStateTuple[currentStateTuple.length-1];
			IntArrayObj currentGlobalVector = globalVecTbl.get(globalVecIndex);
			int[] currentGlobalArray = currentGlobalVector.toArray();
			
			LpnTranList enabledTrans = sg.getEnabled(moduleState, currentGlobalArray, gVarIndexMap, currentGlobalVector.getIndex());
			
//			System.out.println("      ENABLED TRANS: " + enabledTrans);
			
			/*
			 * Fire each enabled LPN transition
			 */
			for(LPNTran enabledTran : enabledTrans){
				Pair<State, HashMap<String,Integer>> nextModState = null;
				nextModState = enabledTran.fire(sg, moduleState, currentGlobalVector.toArray(), currentGlobalVector.getIndex(), gVarIndexMap);
				
				State nextState = nextModState.getLeft();
				HashMap<String, Integer> NewGlobalVecMap = nextModState.getRight();
				
//				System.out.println("      Fired: " + enabledTran.getFullLabel());

				// Check if any global variables were modified
				if(NewGlobalVecMap.size() != 0) {
					/*
					 * Create the next state's global vector with the set of modified global variables
					 */
					int[] nextGlobalArray = Arrays.copyOf(currentGlobalArray, currentGlobalArray.length);
					Set<String> vars = NewGlobalVecMap.keySet();
					for(String var : vars) {
						nextGlobalArray[gVarIndexMap.getValue(var)] = NewGlobalVecMap.get(var);
					}
					
					// Next global state
					IntArrayObj nextGlobalVector = new IntArrayObj(nextGlobalArray);

					// Add next global state to the table of global states
					nextGlobalVector = globalVecTbl.add(nextGlobalVector);
					
					// Add next module state to the module state graph
					nextState = sg.addState(nextState);
//					nextState = moduleStateCache.add(nextState);
					
					int[] nextStateTuple = Arrays.copyOf(currentStateTuple, currentStateTuple.length);
					nextStateTuple[lpn.getIndex()] = nextState.getIndex();
					nextStateTuple[currentStateTuple.length - 1] = nextGlobalVector.getIndex();
					
					CompositeState nextGlobalState = new CompositeState(nextStateTuple);
					
					nextGlobalState = globalStateGraph.addState(nextGlobalState);
					if(nextGlobalState.time2 != currentTime){
						globalStateStack.push(nextGlobalState);
					}
					
					CompositeStateTran globalStateTran = globalStateGraph.addStateTran(currentGlobalState, nextGlobalState, enabledTran);
					if(!enabledTran.local()){
						globalStateTran.setVisibility();
					}
				}
				else{
					// Add next module state to the module state graph
					nextState = sg.addState(nextState);
//					nextState = moduleStateCache.add(nextState);
					
//					System.out.println("      Next State: " + moduleState.getIndex() + " - " + nextState.getIndex());
					
					int[] nextStateTuple = Arrays.copyOf(currentStateTuple, currentStateTuple.length);
					nextStateTuple[lpn.getIndex()] = nextState.getIndex();
					
					CompositeState nextGlobalState = new CompositeState(nextStateTuple);
					
					nextGlobalState = globalStateGraph.addState(nextGlobalState);					
					if(nextGlobalState.time2 != currentTime){
						globalStateStack.push(nextGlobalState);
					}
					
					CompositeStateTran globalStateTran = globalStateGraph.addStateTran(currentGlobalState, nextGlobalState, enabledTran);
					if(!enabledTran.local()){
						globalStateTran.setVisibility();
					}
				}
			}
		}
		
		currentTime++;
	}
	
	private static boolean addCompositeState(HashMap<State, HashSet<IntArrayObj>> stateMap, State localState, IntArrayObj globalState){
		
		if(stateMap.containsKey(localState)){
			HashSet<IntArrayObj> globalSet = stateMap.get(localState);
			return globalSet.add(globalState);
		}
		
		HashSet<IntArrayObj> globalSet = new HashSet<IntArrayObj>();
		globalSet.add(globalState);
		
		stateMap.put(localState, globalSet);
		
		return true;
	}
	
//	private static State addState(StateGraph sg, State localState, IntArrayObj globalState,
//			DualHashMap<String, Integer> gVarIndexMap){
//		
//		State newState = new State(localState);
//		int[] vector = newState.getVector();
//		int[] globalVector = globalState.toArray();
//		
//		LPN lpn = sg.getLpn();
//		DualHashMap<String, Integer> varIndexMap = lpn.getVarIndexMap();
//		
//		// Add global variable values to the state vector
//		for(Entry<String, Integer> e : gVarIndexMap.entrySet()){
//			String var = e.getKey();
//			int gIndex = e.getValue();
//			
//			if(varIndexMap.containsKey(var)){
//				int localIndex = varIndexMap.getValue(var);
//				vector[localIndex] = globalVector[gIndex];
//			}
//		}
//		
//		return sg.addState(newState);
//	
//	private static void addStateTran(StateGraph sg, State currentLocalState, IntArrayObj currentGlobalState,
//			State nextLocalState, IntArrayObj nextGlobalState, LPNTran lpnTran,
//			DualHashMap<String, Integer> gVarIndexMap){
//		
//		State s1 = addState(sg, currentLocalState, currentGlobalState, gVarIndexMap);
//		State s2 = addState(sg, nextLocalState, nextGlobalState, gVarIndexMap);
//		
//		if(s1 == s2){
//			return;
//		}
//		
//		sg.addStateTranCompositional(s1, lpnTran, s2);
//	}
	
	private static void addGlobalTran(IntArrayObj currentGlobalState, IntArrayObj nextGlobalState, LPNTran lpnTran, 
			HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> globalTranTbl){
		
		HashMap<LPNTran, HashSet<IntArrayObj>> tranMap = globalTranTbl.get(currentGlobalState);
		if(tranMap == null){
			tranMap = new HashMap<LPNTran, HashSet<IntArrayObj>>();
			HashSet<IntArrayObj> nextSet = new HashSet<IntArrayObj>();
			nextSet.add(nextGlobalState);
			
			tranMap.put(lpnTran, nextSet);
			globalTranTbl.put(currentGlobalState, tranMap);
		}
		else{
			HashSet<IntArrayObj> nextSet = tranMap.get(lpnTran);
			if(nextSet == null){
				nextSet = new HashSet<IntArrayObj>();
				nextSet.add(nextGlobalState);
				tranMap.put(lpnTran, nextSet);
			}
			else{
				nextSet.add(nextGlobalState);
			}
		}
	}
	
	/**
	 * Performs the new CRA approach using global search
	 * @param designUnitSet - Set of state graphs to construct
	 */
	public static void craGlobalSearch1(StateGraph[] designUnitSet){
		String info = "--> Start compositional reachability analysis (global search v1) ...";
		Console.print(info, Console.MessageType.dynamicInfo, 0);
		
		for(StateGraph sg : designUnitSet){
			LPN lpn = sg.getLpn();
			
			// Add initial state to state graph
			State init = lpn.getInitState();
			init = sg.addState(init);
			sg.setInitialState(init);
		}
		
		IndexObjMap<IntArrayObj> globalVecTbl = new IndexObjMap<IntArrayObj>();
		
		/* 
		 * Initialize the gVarIndexMap and gVec 
		 */		
		int gVarIdx = 0;
		DualHashMap<String, Integer> gVarIndexMap = new DualHashMap<String,Integer>();
		HashMap<String, Integer> initGlobalVecMap = new HashMap<String,Integer>();

		for(Pair<String, Integer> gvar : globalVarList) {
			gVarIndexMap.insert(gvar.getLeft(), gVarIdx);
			gVarIdx++;
			initGlobalVecMap.put(gvar.getLeft(), gvar.getRight());
		}

		for(int i = 0; i< designUnitSet.length; i++) {
			StateGraph curSG = designUnitSet[i];
			curSG.initialize();

			VarSet curGlobals = curSG.getLpn().getGlobals();
			HashMap<String,Integer> curInitVec = curSG.getLpn().getInitVector();
			for(String gvar : curGlobals) {
				if(gVarIndexMap.containsKey(gvar)==false) {
					gVarIndexMap.insert(gvar, gVarIdx++);
					initGlobalVecMap.put(gvar, curInitVec.get(gvar));
				}
			}
		}
		
		/*
		 * 
		 */
		HashMap<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> globalTranTbl = 
			searchDfsUnderApprox(designUnitSet, globalVecTbl, gVarIndexMap, initGlobalVecMap);
		
		int iter = 0;
		int previousNumGlobalStates = -1;
		
		// Terminate using global states or globalStateTrans?
		while(globalVecTbl.size() > previousNumGlobalStates){
			iter++;
			
			System.out.println("Iteration " + iter);
			System.out.println("   previousNumGlobalStates: " + previousNumGlobalStates);
			System.out.println("   globalVecTbl.size(): " + globalVecTbl.size());
			
			previousNumGlobalStates = globalVecTbl.size();
			
			for(StateGraph sg : designUnitSet){
				globalSearch1(sg, globalVecTbl, globalTranTbl, gVarIndexMap, initGlobalVecMap);
				System.out.println("\t\t # of process states: " + sg.stateCount());
			}
		}
		
		int stateTrans = 0;
		for(Entry<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> e1 : globalTranTbl.entrySet()){
			for(Entry<LPNTran, HashSet<IntArrayObj>> e2 : e1.getValue().entrySet()){
				stateTrans += e2.getValue().size();
			}
		}
		
		System.out.println("\n# Global State: " + globalVecTbl.size());
		System.out.println("# Global State Transitions: " + stateTrans);
		
//		for (StateGraph sg : designUnitSet) {
//			System.out.print("   ");
//			sg.printStates();
//		}
		
//		System.out.println();
//		
//		for(Entry<IntArrayObj, HashMap<LPNTran, HashSet<IntArrayObj>>> e1 : globalTranTbl.entrySet()){
//			for(Entry<LPNTran, HashSet<IntArrayObj>> e2 : e1.getValue().entrySet()){
//				for(IntArrayObj o : e2.getValue()){
//					System.out.println(e1.getKey() + " - " + e2.getKey().getFullLabel() + " - " + o);
//				}
//			}
//		}
	}
	
	/**
	 * Performs the new CRA approach using global search
	 * @param designUnitSet - Set of state graphs to construct
	 */
	public static void craGlobalSearch(StateGraph[] designUnitSet){
		String info = "--> Start compositional reachability analysis (global search) ...";
		Console.print(info, Console.MessageType.dynamicInfo, 0);
		
		// Timing and memory usage info
		long startTime = System.currentTimeMillis();
		long peakMemUsed = 0;
		long peakMemTotal = 0;
		
		int[] initGlobalState = new int[designUnitSet.length + 1];
		
		int index = 0;
		for(StateGraph sg : designUnitSet){
			LPN lpn = sg.getLpn();
			
			// Add initial state to state graph
			State init = lpn.getInitState();
			init = sg.addState(init);
			sg.setInitialState(init);
			initGlobalState[index++] = init.getIndex();
		}
		
		IndexObjMap<IntArrayObj> globalVecTbl = new IndexObjMap<IntArrayObj>();
		
		/* 
		 * Initialize the gVarIndexMap and initGlobalVecMap
		 */		
		int gVarIdx = 0;
		DualHashMap<String, Integer> gVarIndexMap = new DualHashMap<String,Integer>();
		HashMap<String, Integer> initGlobalVecMap = new HashMap<String,Integer>();

		for(Pair<String, Integer> gvar : globalVarList) {
			gVarIndexMap.insert(gvar.getLeft(), gVarIdx);
			gVarIdx++;
			initGlobalVecMap.put(gvar.getLeft(), gvar.getRight());
		}

		for(int i = 0; i< designUnitSet.length; i++) {
			StateGraph curSG = designUnitSet[i];
			curSG.initialize();

			VarSet curGlobals = curSG.getLpn().getGlobals();
			HashMap<String,Integer> curInitVec = curSG.getLpn().getInitVector();
			for(String gvar : curGlobals) {
				if(gVarIndexMap.containsKey(gvar)==false) {
					gVarIndexMap.insert(gvar, gVarIdx++);
					initGlobalVecMap.put(gvar, curInitVec.get(gvar));
				}
			}
		}
		
		/*
		 * Create the initial global vector
		 */
		int[] initGlobalVec = makeVec(initGlobalVecMap, gVarIndexMap);
		IntArrayObj curGlobalVecObj = globalVecTbl.add(new IntArrayObj(initGlobalVec));
		
		/*
		 * Create the initial global state
		 */
		initGlobalState[index] = curGlobalVecObj.getIndex();
		CompositeState initState = new CompositeState(initGlobalState);
		
		/*
		 * Create the global state graph
		 */
		CompositeStateGraph globalStateGraph = new CompositeStateGraph(initState, designUnitSet);
		
		int iter = 0;
		boolean foundNewStates = true;

		// Terminate using global states or globalStateTrans?
		while(foundNewStates){
			foundNewStates = false;
			iter++;

			System.out.println("Iteration " + iter);
//			System.out.println("   # Global States: " + globalStateGraph.numStates());
			System.out.println("   # Global States: " + globalStateGraph.numStates());
			System.out.println("   # Global State Trans: " + globalStateGraph.numStateTrans());
			
			int prevNumStates = globalStateGraph.numStates();
			
			for(StateGraph sg : designUnitSet){
//				System.out.println("   Module: " + sg.getLpn().getLabel());
				
				globalSearch(sg, globalStateGraph, globalVecTbl, gVarIndexMap, initGlobalVecMap);
				
				System.out.println("\t\t # of process states: " + sg.stateCount());

				long curTotalMem = Runtime.getRuntime().totalMemory();
				if(curTotalMem > peakMemTotal){
					peakMemTotal = curTotalMem;
				}
				
				long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				if(curUsedMem > peakMemUsed){
					peakMemUsed = curUsedMem;
				}
				
				/*
				 * Perform abstraction on the global state graph
				 */
				
//				CompositionalMinimization.globalSearchAbstraction(globalStateGraph);
				
				for(CompositeState currentState : globalStateGraph.getStateSet()){
					currentState.flag = false;
					currentState.time = -1;
					currentState.status = -1;
					currentState.trav = false;
				}
				
				curTotalMem = Runtime.getRuntime().totalMemory();
				if(curTotalMem > peakMemTotal){
					peakMemTotal = curTotalMem;
				}
				
				curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
				if(curUsedMem > peakMemUsed){
					peakMemUsed = curUsedMem;
				}
			}
			
			if(globalStateGraph.numStates() > prevNumStates){
				foundNewStates = true;
			}
			
//			if(iter == 2){
//				break;
//			}
		}
		
//		globalStateGraph.draw();
		
		System.out.println("\n   --> # states: " + globalStateGraph.numStates());
		System.out.println("   --> # transitions: " + globalStateGraph.numStateTrans());
		System.out.println("   --> # iterations: " + iter);
		
		System.out.println("\n   --> Peak used memory: " + peakMemUsed/1000000F + " MB");
		System.out.println("   --> Peak total memory: " + peakMemTotal/1000000F + " MB");
		System.out.println("   --> Final used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB");
		
		long elapsedTimeMillis = System.currentTimeMillis()-startTime; 
		float elapsedTimeSec = elapsedTimeMillis/1000F;
		System.out.println("   --> Elapsed time: " + elapsedTimeSec + " sec");
		
		if(elapsedTimeSec > 60){
			float elapsedTime = elapsedTimeSec/(float)60;
			System.out.println("   --> Elapsed time: " + elapsedTime + " min");
		}
	}
	
	/**
	 * Constructs the compositional state graphs.
	 * Implements an improved version of compositional search.
	 * @param designUnitSet - Set of state graphs to construct
	 */
	public static void craConstr(StateGraph[] designUnitSet){
		String info = "--> Start compositional reachability analysis ...";
		Console.print(info, Console.MessageType.dynamicInfo, 0);
		
		
		/*
		 * Initializes the private static attributes used for compositional reachability analysis
		 */
		setupAttributes(designUnitSet);
		
		// Keeps track of the current iteration
		int iter = 0;
		
		// Keeps track of the number of new state transitions generated during an iteration
		int newTransitions = 0;
		
		// Maps a SG to a list of other SGs that modify its inputs
		HashMap<StateGraph, List<StateGraph>> inputSrcMap = new HashMap<StateGraph, List<StateGraph>>();
		
		// Timing and memory usage info
		long startTime = System.currentTimeMillis();
		long peakMemUsed = 0;
		long peakMemTotal = 0;
		
		/*
		 * Initialize each module's state graph.
		 * Add the initial state, find input sources and add to inpuSrcMap, 
		 * and also find the common interface between every pair of state graphs.
		 */
		for (StateGraph sg : designUnitSet) {
			LPN lpn = sg.getLpn();
			
            // Add initial state to state graph
			State init = lpn.getInitState();
			init = sg.addState(init);
			sg.setInitialState(init);
			addFrontierState(sg, init);
            
			// List of LPNs that modify the current LPN
			List<StateGraph> srcArray = new ArrayList<StateGraph>();

			// determine the LPNs that modify the current LPN
			for(StateGraph sg2 : designUnitSet){
				if(sg == sg2){
					continue;
				}
				
				LPN lpn2 = sg2.getLpn();
				if(lpn2.modifies(lpn)){
					// find the common interface
					Pair<int[], int[]> indexPair = lpn2.constrInterfacePair(lpn);
					setInterfacePair(lpn2, lpn, indexPair);
					srcArray.add(sg2);	
				}
			}

			inputSrcMap.put(sg, srcArray);
		}		
		
		/*
		 * Run findSG() from each module's initial state
		 */
		for (StateGraph sg : designUnitSet) {
			int result = 0;
			if(Options.getStickySemantics()){
//				result = sg.constrStickyFindSG(sg.getInitialState(), emptyTranList);
			}
			else{
				result = buildModuleSG(sg, sg.getInitialState());
			}
			
			newTransitions += result;
		}

		// contains the threads used to do the work in parallel
		CompositionalThread[] threadArray = new CompositionalThread[designUnitSet.length];
		
		// contains the set of new constraints generated in the last iteration
		List<Constraint> newConstraintSet = new ArrayList<Constraint>();
		
		// contains the set of old constraints generated in the previous iterations
		List<Constraint> oldConstraintSet = new ArrayList<Constraint>();
		
		/*
		 * Continue CRA until no new state transitions are generated.
		 */
		int iterations = 0;
		while(newTransitions > 0){
			iterations++;
			if(Options.getVerbosity() > 20) {
				String msg = "\t" + "iteration " + iter + ":\n";
				//System.out.println("   " + newTransitions);
				for(StateGraph sg : designUnitSet){
					msg += "\t    Module " + sg.getLpn().getLabel() + ":  |S| =  " + sg.reachSize() + ",  |T| = " + sg.numTransitions() +"\n";
				}
				System.out.println(msg);
			}
			
			newTransitions = 0;
			iter++;
			
			/*
			 * Update dynamic attributes that change within each iteration (frontier sets, etc...)
			 */
			for(StateGraph sg : designUnitSet){
				updateConstraints(sg);
				updateStateFrontier(sg);
			}

			if(Options.getParallelFlag() == true){
				int t = 0;
				
				// create a new thread for each SG to do its own work
				for(StateGraph sg : designUnitSet){
					CompositionalThread newThread = new CompositionalThread(sg, inputSrcMap.get(sg));
				    newThread.start();
				    threadArray[t++] = newThread;
				}

				// join the threads (wait for each thread to finish work)
				for(CompositionalThread p : threadArray){
					try {
						p.join();
						newTransitions += p.getNewTransitions();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			else{
				for(StateGraph sg : designUnitSet){
					for(StateGraph srcSG : inputSrcMap.get(sg)){
						if (true) {//Options.getSearchType() == SearchTypeDef.CRA) {
							extractConstraints(sg, srcSG, newConstraintSet, oldConstraintSet);
							newTransitions += applyConstraintSet(sg, srcSG, newConstraintSet, oldConstraintSet);
						}
						else{ // Original compositional search algorithm
							extractConstraintsOrig(sg, srcSG, oldConstraintSet);
							newTransitions += applyConstraintSetOrig(sg, srcSG, oldConstraintSet);
						}
					}
				}
			}

			long curTotalMem = Runtime.getRuntime().totalMemory();
			if(curTotalMem > peakMemTotal){
				peakMemTotal = curTotalMem;
			}
			
			long curUsedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
			if(curUsedMem > peakMemUsed){
				peakMemUsed = curUsedMem;
			}
			
			//* Runtime info output
			System.out.println("---> Iteration " + iterations);
			for (StateGraph sg : designUnitSet) {
				System.out.print("  ");
				sg.printStates();
			}
			System.out.println();
		}

		//* Print the stats of all SGs generated by this CRA procedure. after the iteration is done.
		int numStates = 0;
		int numTrans = 0;
		int numConstr = 0;
		for (StateGraph sg : designUnitSet) {
			updateConstraints(sg);
			updateStateFrontier(sg);
			
			if(Options.getVerbosity() > 0){
				System.out.print("  ");
				sg.printStates();
			}
			
			numStates += sg.reachSize();
			numTrans += sg.numTransitions();
			numConstr += numConstraints(sg);
		}
		
		if(Options.getVerbosity() > 0){
			System.out.println("\n   --> # states: " + numStates);
			System.out.println("   --> # transitions: " + numTrans);
			System.out.println("   --> # constraints: " + numConstr);
			System.out.println("   --> # iterations: " + iter);
			
			System.out.println("\n   --> Peak used memory: " + peakMemUsed/1000000F + " MB");
			System.out.println("   --> Peak total memory: " + peakMemTotal/1000000F + " MB");
			System.out.println("   --> Final used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())/1000000F + " MB");
			
			long elapsedTimeMillis = System.currentTimeMillis()-startTime; 
			float elapsedTimeSec = elapsedTimeMillis/1000F;
			System.out.println("   --> Elapsed time: " + elapsedTimeSec + " sec");
			
			if(elapsedTimeSec > 60){
				float elapsedTime = elapsedTimeSec/(float)60;
				System.out.println("   --> Elapsed time: " + elapsedTime + " min");
			}
		}
	}
	
    /**
     * Finds reachable states from the given state in a DFS manner.
     * Also generates new constraints from the state transitions.
     * @param baseState - State to start from
     * @return Number of new state transitions
     */
    public static int buildModuleSG(StateGraph sg, final State baseState){
    	// Do not process error states
    	if(baseState.getErrorCode() != StateError.NONE){
    		return 0;
    	}
    	
    	// keeps track of the number of new state transitions generated
        int newTransitions = 0;
        
        // stack used to process states in a DFS manner
        Stack<State> stateStack = new Stack<State>();
        
        // stack used to hold the state's enabled transition set

        // LPN associated with the state graph being generated
        LPN lpn = sg.getLpn();
        
        /*
         * Initialize the stacks with the base state it's enabled transition set
         */
        stateStack.push(baseState);

        /*
         * Process the next state on the stack until it is empty
         */
        while (!stateStack.empty()){
            State currentState = stateStack.pop();
            LpnTranList currentEnabledTransitions = sg.getEnabled(currentState, false);
            
            int arrayIndex = 0;
            StateTran[] outgoingStateTranArray = new StateTran[currentEnabledTransitions.size()];
            
            for (LPNTran firedTran : currentEnabledTransitions) {
            	// Fire the LPN transition from the current state
                State newState = firedTran.constrFire(currentState);
                
                /*
            	 * If an error does not already exist, check disabling error if the option is set.
            	 */
            	if(newState.getErrorCode() == StateError.NONE){
	                if(Options.checkDisablingError()){
	                	LpnTranList nextEnabledTransitions = sg.getEnabled(newState, false);
		                
	                	LPNTran disabledTran = firedTran.disablingError1(currentEnabledTransitions, nextEnabledTransitions);
		                if(disabledTran != null) {
		                	if(Options.getVerbosity() > 25){
		                		System.err.println(disabledTran.getFullLabel() + " is disabled by " + firedTran.getFullLabel());
		                	}
		                	
		                	newState = sg.disablingErrorState;
		                }
	                }
            	}
            	
                /*
                 * If the next state already exists get the existing state in the state graph
                 */
                State nextState = sg.getState(newState);
                if(nextState == null){
                	nextState = newState;
                }
                
                /*
                 * Add the state transition to the state graph.
                 * If it is not new continue to the next enabled LPN transition.
                 */
                StateTran stTran = sg.addStateTranCompositional(currentState, firedTran, nextState);
        		if(stTran == null){
        			continue;
        		}
            	
            	/*
            	 * Perform partial auto failure reduction by marking the current state as the error
            	 * state, removing each outgoing state transition from the current state, and removing
            	 * unreachable states.
            	 */
            	if(Options.getAutoFailureFlag() && nextState.getErrorCode() != StateError.NONE){            		
            		/*
            		 * Remove outgoing state transitions.
            		 */           		
            		for(int i = 0; i < arrayIndex; i++){
            			StateTran outgoingTran = outgoingStateTranArray[i];
            			sg.removeStateTran(outgoingTran);
            		}
            		System.out.println("afr " + Options.getAutoFailureFlag());

            		arrayIndex = 0;
            		outgoingStateTranArray[arrayIndex++] = stTran;
            		break;
            	}
            	
            	// Store the state transition as one of the current state's outgoing transitions
            	outgoingStateTranArray[arrayIndex++] = stTran;
            }
            
            /*
             * Add the next states to the state graph
             */
            for(int i = 0; i < arrayIndex; i++){
            	StateTran stTran = outgoingStateTranArray[i];
            	LPNTran firedTran = stTran.getLpnTran();
            	State nextState = stTran.getNextState();
            	
            	// increment newTransitions for the newly added state transition
            	newTransitions++;
            	
            	// check if the next state is a new state
            	boolean newStateFlag = true;
            	if(sg.containsState(nextState)){
            		newStateFlag = false;
            	}
            	
            	/*
            	 * add new state to the SG and frontier set
            	 */
            	if(newStateFlag == true){
                	sg.addState(nextState);
                	
                	if(nextState.getErrorCode() == StateError.NONE){
                		addFrontierState(sg, nextState);
                	}
            	}
            	
            	if(nextState.getErrorCode() != StateError.NONE){
            		continue;
            	}
            	
            	/*
        		 * If the fired LPN transition is non-local, generate a constraint 
                 * for each destination LPN
        		 */
            	if(!firedTran.local()){
            		for(LPN dstLpn : firedTran.getDstLpnList()){
            			Pair<int[], int[]> indexPair = getInterfacePair(lpn, dstLpn);
                  		Constraint c = new Constraint(currentState, nextState, firedTran, dstLpn, indexPair.getLeft());
                  		
                  		if(Options.getParallelFlag() == true){
                  			synchronizedAddConstraint(dstLpn.getStateGraph(), c);
                  		}
                  		else{
                  			addConstraint(dstLpn.getStateGraph(), c);
                  		}
        			}
            	}
            	
            	if(newStateFlag == false){
            		continue;
            	}
            	
            	LpnTranList nextEnabledTransitions = sg.getEnabled(nextState, false);
            	if(nextEnabledTransitions.size() == 0){
            		continue;
            	}
            	
            	/*
                 * Add the next state and it's enabled LPN transitions to the stack for DFS
                 */
                stateStack.push(nextState);
            }
        }

        return newTransitions;
    }
  
    /**
     * Returns the interface pair between an LPN that modifies an input variable of the other LPN 
     * and an LPN that takes x as an input.
     * @param thisLpn - Source LPN
     * @param otherLpn - Destination LPN
     * @return Interface pair
     */
    public static Pair<int[], int[]> getInterfacePair(LPN thisLpn, LPN otherLpn){
    	return interfacePairList.get(thisLpn.getIndex()).get(otherLpn.getIndex());
    }
    
    /**
     * Stores the interface pair between an LPN that modifies an input variable of the other LPN
     * @param thisLPN - Source LPN
     * @param otherLpn - Destination LPN
     */
    private static void setInterfacePair(LPN thisLpn, LPN otherLpn, Pair<int[], int[]> indexPair){
    	interfacePairList.get(thisLpn.getIndex()).set(otherLpn.getIndex(), indexPair);
    }
    
	/**
	 * Applies new constraints to the entire state set, and applies old constraints to the frontier state set.
     * @return Number of new transitions.
     */
	public static int applyConstraintSet(StateGraph sg, StateGraph srcSG, List<Constraint> newConstraintSet, List<Constraint> oldConstraintSet){
		int newTransitions = 0;
		LPN srcLpn = srcSG.getLpn();
		LPN lpn = sg.getLpn();

		Pair<int[], int[]> indexPair = getInterfacePair(srcLpn, lpn);
		int[] srcIndexList = indexPair.getLeft();
		int[] currentIndexList = indexPair.getRight();
		
		if(newConstraintSet.size() > 0){
			List<State> stateSet = getStateSet(sg);
			for(int i=0; i< stateSet.size(); i++){
				State currentState = stateSet.get(i);
				if(currentState.getErrorCode() != StateError.NONE){
					continue;
				}
				
				for(int j=0; j<newConstraintSet.size(); j++ ){
					Constraint constr = newConstraintSet.get(j);
					
					if(compatible(currentState, constr, currentIndexList, srcIndexList)){
						newTransitions += createNewState(sg, currentState, constr);
					}
				}
			}

			List<State> frontierList = getFrontierStateSet(sg);
			for(int i=0; i<frontierList.size(); i++){
				State currentState = frontierList.get(i);
				if(currentState.getErrorCode() != StateError.NONE){
					continue;
				}
				
				for(int j=0; j<newConstraintSet.size(); j++){
					Constraint c = newConstraintSet.get(j);
					
					if(compatible(currentState, c, currentIndexList, srcIndexList)){						
						newTransitions += createNewState(sg, currentState, c);
					}
				}
			}
		}
		
		if(oldConstraintSet.size() > 0){
			List<State> frontierList = getFrontierStateSet(sg);
			for(int i=0; i<frontierList.size(); i++){
				State currentState = frontierList.get(i);
				if(currentState.getErrorCode() != StateError.NONE){
					continue;
				}
				
				for(int j=0; j<oldConstraintSet.size(); j++){
					Constraint c = oldConstraintSet.get(j);
					
					if(compatible(currentState, c, currentIndexList, srcIndexList)){						
						newTransitions += createNewState(sg, currentState, c);
					}
				}
			}
		}
		
		return newTransitions;
	}
	
	/**
	 * Applies new constraints to the entire state set, and applies old constraints to the frontier state set.
     * @return Number of new transitions.
     */
	public static int applyConstraintSetOrig(StateGraph sg, StateGraph srcSG, List<Constraint> constraintSet){
		int newTransitions = 0;
		LPN srcLpn = srcSG.getLpn();
		LPN lpn = sg.getLpn();

		Pair<int[], int[]> indexPair = getInterfacePair(srcLpn, lpn);
		int[] srcIndexList = indexPair.getLeft();
		int[] currentIndexList = indexPair.getRight();
		
		if(constraintSet.size() > 0){
			for(State currentState : getStateSet(sg)){
				if(currentState.getErrorCode() != StateError.NONE){
					continue;
				}
				
				for(Constraint c : constraintSet){
					if(compatible(currentState, c, currentIndexList, srcIndexList)){
						newTransitions += createNewState(sg, currentState, c);
					}
				}
			}
		}
		
		return newTransitions;
	}
	
	/**
     * Extracts applicable constraints from a StateGraph.
     * @param sg The state graph the constraints are to be applied.
     * @param srcSG The state graph the constraint are extracted from.
     */
	public static void extractConstraints(StateGraph sg, StateGraph srcSG, List<Constraint> newConstraintSet, List<Constraint> oldConstraintSet){
		newConstraintSet.clear();
		oldConstraintSet.clear();
		
		LPN srcLpn = srcSG.getLpn();
		
		List<Constraint> newConstraintList = getNewConstr(sg);
		for(int i=0; i<newConstraintList.size(); i++){
			Constraint newConstraint = newConstraintList.get(i);
			if(newConstraint.getLpn() != srcLpn){
				continue;
			}
	    	
			newConstraintSet.add(newConstraint);
		}
		
		List<Constraint> oldConstraintList = getOldConstr(sg);
		for(int i=0; i<oldConstraintList.size(); i++){
			Constraint oldConstraint = oldConstraintList.get(i);
			if(oldConstraint.getLpn() != srcLpn){
				continue;
			}
			
			oldConstraintSet.add(oldConstraint);
		}
	}
	
	/**
     * Extracts applicable constraints from a StateGraph.  
     * Used for the original version of searchCompositional.
     * @param sg The state graph the constraints are to be applied.
     * @param srcSG The state graph the constraint are extracted from.
     */
	public static void extractConstraintsOrig(StateGraph sg, StateGraph srcSG, List<Constraint> constraintSet){
		constraintSet.clear();
		
		LPN srcLpn = srcSG.getLpn();		
		for(Constraint oldConstraint : getOldConstr(sg)){
			if(oldConstraint.getLpn() != srcLpn){
				continue;
			}
			
			constraintSet.add(oldConstraint);
		}
	}
	
	/**
     * Determines whether a constraint is compatible with a state.
     * @return True if compatible, otherwise False.
     */
	private static boolean compatible(State currentState, Constraint constr, int[] thisIndexList, int[] otherIndexList){
		int[] constraintVector = constr.getVector();
		int[] currentVector = currentState.getVector();
		
		for(int i = 0; i < thisIndexList.length; i++){
			int thisIndex = thisIndexList[i];
			int otherIndex = otherIndexList[i];

			if(currentVector[thisIndex] != constraintVector[otherIndex]){
				return false;
			}
		}
		
		return true;
	}
	
	/**
     * Creates a state from a given constraint and compatible state, and adds it to the state graph.  
     * If the state is new, then DFS is performed.
     * @return Number of new transitions.
     */
	private static int createNewState(StateGraph sg, State compatibleState, Constraint c){
		int newTransitions = 0;

		// Create the new state start point from the compatible state
		State newState = new State(compatibleState);
		int[] newVector = newState.getVector();
		
		// list of variables that need to be updated
		List<VarNode> variableList = c.getVariableList();
		
		// list of variable values to update
		List<Integer> valueList = c.getValueList();
		
		// update the new state's vector
		int[] compatibleVector = compatibleState.getVector();
		for(int i = 0; i < variableList.size(); i++){
			int index = variableList.get(i).getIndex(compatibleVector);
			newVector[index] = valueList.get(i);
		}
		
		LPNTran constraintTran = c.getLpnTransition();
		
		/*
		 * Check disabling error if the option is set.
		 */   
        if(Options.checkDisablingError()){
        	LpnTranList currentEnabledTransitions = sg.getEnabled(compatibleState, false);
        	LpnTranList nextEnabledTransitions = sg.getEnabled(newState, false); 

        	LPNTran disabledTran = constraintTran.disablingError1(currentEnabledTransitions, nextEnabledTransitions);
            if(disabledTran != null) {
            	if(Options.getVerbosity() > 25){
            		System.err.println(disabledTran.getFullLabel() + " is disabled by " + 
                			constraintTran.getFullLabel());
            	}
            	
                newState = sg.disablingErrorState;
            }
        }
        
		// add the new state to the state graph
		State nextState = sg.addState(newState);
		
		// add the state transition to the state graph
		StateTran stTran = sg.addStateTranCompositional(compatibleState, constraintTran, nextState);
		
		/*
		 * If the state transition was added, increment newTransitions and check for disabling error
		 * if the next state does not already contain an error.
		 */
		if(stTran != null){
			newTransitions++;
		}
		
		/*
		 * If newState was added to the state graph (a new state), 
		 * add the state to the frontier set and perform dfs search.
		 */
		if(nextState == newState && nextState.getErrorCode() == StateError.NONE){
			addFrontierState(sg, nextState);

			if(Options.getStickySemantics()){
//				newTransitions += sg.constrStickyFindSG(nextState, sg.getEnabled(compatibleState));
			}
			else{
				newTransitions += buildModuleSG(sg, nextState);
			}
		}

		return newTransitions;
	}
}

