-- AS-1: seed three approved process paths (pp_*)
-- Ops use stable op_codes; order in JSON array is the path order.

INSERT INTO process_path (process_path_id, content, description) VALUES
(
  'pp_full_eol',
  CAST('{"name":"Full EOL","ops":["LOADER","COAT","UV","DEPANEL","CURE","ASM","COLD_EOL","HOT_EOL","AMBIENT_EOL","PACK"]}' AS JSON),
  'Full EOL (legacy R1): Cold → Hot → Ambient → Pack'
),
(
  'pp_cold_ambient',
  CAST('{"name":"Cold + Ambient","ops":["LOADER","COAT","UV","DEPANEL","CURE","ASM","COLD_EOL","AMBIENT_EOL","PACK"]}' AS JSON),
  'Cold + Ambient (legacy R2): Hot not on path'
),
(
  'pp_ambient_only',
  CAST('{"name":"Ambient only","ops":["LOADER","COAT","UV","DEPANEL","CURE","ASM","AMBIENT_EOL","PACK"]}' AS JSON),
  'Ambient only (legacy R3): Ambient → Pack'
);
