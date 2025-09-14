# Social ABM - Agent-Based Modeling Framework

A Clojure framework for agent-based modeling of social systems, inspired by Sugarscape and NetLogo.

## Features

- **Protocol-based architecture** for flexible agent and world implementations
- **Grid-based world system** with configurable dimensions and cell values
- **Agent behaviors** including movement, resource consumption, and social interactions
- **Simulation runner** with statistics tracking and visualization helpers
- **Sugarscape example** demonstrating classic agent-based modeling concepts

## Project Structure

```
src/
├── social_abm/
│   ├── core.clj          # Core protocols and entry point
│   ├── world.clj         # Grid world implementation
│   ├── agent.clj         # Basic and social agent implementations
│   ├── simulation.clj    # Simulation runner and utilities
│   └── examples/
│       └── sugarscape.clj # Sugarscape-inspired model
```

## Quick Start

1. **Run the Sugarscape demo:**
   ```bash
   lein repl
   ```
   ```clojure
   (require '[social-abm.examples.sugarscape :as sugar])
   (sugar/demo)
   ```

2. **Create your own simulation:**
   ```clojure
   (require '[social-abm.world :as w]
            '[social-abm.agent :as a]
            '[social-abm.simulation :as sim])

   ;; Create a 20x20 world
   (def world (w/create-grid-world 20 20))

   ;; Add some agents
   (def world-with-agents
     (-> world
         (.add-agent (a/create-basic-agent 1 5 5))
         (.add-agent (a/create-basic-agent 2 10 10))))

   ;; Run simulation for 100 steps
   (sim/run-simulation world-with-agents 100)
   ```

## Core Concepts

### Agents
Implement the `Agent` protocol with:
- `step` - Execute one timestep of behavior
- `get-position` / `set-position` - Position management

### World
Implement the `World` protocol with:
- Grid cell access and modification
- Agent management
- Neighbor finding
- World stepping

### Simulation
- Run multi-step simulations
- Statistics tracking and logging
- ASCII visualization
- Custom step functions for model-specific logic

## Examples

### Sugarscape Model
A classic agent-based model where agents:
- Move around a landscape consuming sugar
- Have limited vision and energy
- Age and die naturally
- Compete for limited resources

Run with: `(social-abm.examples.sugarscape/demo)`

## Development

```bash
# Start REPL
lein repl

# Run tests
lein test

# Build uberjar
lein uberjar
```

## License

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0