# Instanced Dimensions Rewrite Plan

This mod is being rebuilt as an engine overhaul centered on runtime-created `ServerLevel` instances.

## Phase 1
- Quarantine the legacy implementation under `obsolete/legacy_mod`
- Restore a minimal active mod shell
- Rename the mod display name to `Instanced Dimensions`

GameTests:
- `bootstrap_smoke_test`
- Goal: mod loads and the active source tree compiles cleanly

## Phase 2
- Define stable runtime-facing services
- Freeze the public contract around runs, travel, and instances
- Add migration boundaries for legacy save data

GameTests:
- `obelisk_activation_roundtrip`
- `obelisk_cooldown_blocks_reentry`
- `run_return_mechanism_works`

## Phase 3
- Patch runtime `ServerLevel` creation
- Queue world add/remove on the server thread
- Introduce authoritative instance metadata and storage

GameTests:
- `instance_create_ticks_and_unloads`
- `instance_storage_isolated_per_level_key`
- `instance_destroy_does_not_touch_shared_dimensions`

## Phase 4
- Patch runtime client awareness of instance worlds
- Validate login, respawn, and repeated cross-instance transfers

GameTests:
- `player_can_enter_runtime_created_instance_after_join`
- `player_can_chain_multiple_instance_transfers`
- `two_players_can_enter_distinct_instances_without_desync`

## Phase 5
- Replace coordinate sharding with instance-backed runs
- Remove destructive region-file deletion
- Move run teardown to proper save/unload/dispose

GameTests:
- `obelisk_creates_real_instance`
- `run_empty_unloads_instance_cleanly`
- `forced_collapse_returns_players_and_unloads_instance`

## Phase 6
- Reconnect FE, rewards, boss bars, mob logic, and commands to the new core
- Preserve the external gameplay contract while changing internals

GameTests:
- `bossbar_tracks_instance_run`
- `fe_depletion_ejects_players`
- `reward_spawn_occurs_at_origin_obelisk`
- `mob_difficulty_applies_only_inside_target_instance`

## Phase 7
- Add legacy save migration and stale-run recovery
- Remove compatibility shims after migration stabilizes

GameTests:
- `legacy_obelisk_nbt_loads_into_new_runtime`
- `legacy_player_run_state_recovers_safely`
- `stale_legacy_runs_do_not_crash_or_leak`
