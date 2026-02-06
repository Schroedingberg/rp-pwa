# progressive - Track your progress

A local-first Progressive Web App for workout tracking, built with ClojureScript.

This is a rewrite of [romance-progression](https://github.com/schroedingberg/romance-progression) - a server-based Clojure app. This PWA version eliminates server dependencies, working entirely offline in the browser while preserving the core event-sourcing architecture.

## Why This Exists

- **Learn Clojure(Script)** by solving a real problem I understand well
- **Own my data** - no proprietary apps, no cloud dependencies
- **Simple architecture** - event sourcing makes the core logic ~200 lines

## Features

- **Offline-first**: Works without internet connection
- **Local storage**: All data persisted in browser localStorage
- **Event sourcing**: Immutable event log with DataScript
- **Plan templates**: Customizable workout plans with swappable progression algorithms

## Tech Stack

- **ClojureScript** with shadow-cljs
- **Reagent** for React-based UI
- **DataScript** for in-browser database
- **Pico CSS** for minimal styling
- **Service Worker** for offline support

## Development

### Prerequisites

- Node.js (v18+)

### Setup

```bash
npm install
npm run dev
```

The app will be available at http://localhost:3000

### Commands

| Command | Description |
|---------|-------------|
| `npm run dev` | Start development server with hot reload |
| `npm run release` | Build optimized production bundle |
| `npm run test` | Run tests once |
| `npm run test:watch` | Run tests in watch mode |
| `npm run clean` | Remove compiled artifacts |
| `npm run docs` | Start Clerk in watch mode |
| `npm run docs:build` | Build static documentation |

### Documentation (Clerk notebooks)

Living documentation is built with [Clerk](https://clerk.vision). To develop locally:

```bash
npm run docs
```

To build static HTML:

```bash
npm run docs:build
```

Output goes to `docs/clerk/`.

## Project Structure

```
├── shadow-cljs.edn       # Build config and dependencies
├── package.json          # Node dependencies and scripts
├── resources/public/
│   ├── index.html        # HTML entry point
│   ├── manifest.json     # PWA manifest
│   └── sw.js             # Service worker
├── src/rp/
│   ├── core.cljs         # App entry point
│   ├── db.cljs           # DataScript event store
│   ├── plan.cljs         # Workout plan templates
│   ├── state.cljs        # State reconstruction
│   ├── storage.cljs      # localStorage persistence
│   ├── ui.cljs           # Reagent components
│   └── util.cljs         # Utility functions
└── test/rp/
    └── state_test.cljs   # Tests
```

## Architecture

The app uses an **event sourcing** pattern:

1. User actions create immutable events (e.g., "set completed")
2. Events are stored in DataScript and persisted to localStorage
3. Current state is reconstructed by replaying events against the plan template

**Key insight**: Your workout log is just a flat list of events. To show progress, we transform events into a nested structure matching the plan, then deep-merge them. This means you can deviate from the plan (extra sets, different exercises) and your data stays intact. You can also swap progression algorithms without data migration.

## License

MIT
