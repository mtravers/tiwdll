(ns social-abm.browser.protocols
  "Core protocols for agent-based modeling in ClojureScript")

(defprotocol Agent
  "Protocol for agents in the simulation"
  (step [agent world] "Execute one step of the agent's behavior")
  (get-position [agent] "Get the agent's current position")
  (set-position [agent pos] "Set the agent's position"))

(defprotocol World
  "Protocol for the simulation world/environment"
  (get-agents [world] "Get all agents in the world")
  (add-agent [world agent] "Add an agent to the world")
  (remove-agent [world agent] "Remove an agent from the world")
  (get-cell [world x y] "Get the contents of a cell")
  (set-cell [world x y value] "Set the contents of a cell")
  (get-neighbors [world x y radius] "Get neighboring cells within radius")
  (step-world [world] "Execute one simulation step"))