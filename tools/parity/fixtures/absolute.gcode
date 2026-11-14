; CONFIG_BLOCK_START
; gcode_flavor = klipper
; layer_height = 0.2
; nozzle_temperature = 210
; CONFIG_BLOCK_END
; estimated printing time (normal mode) = 2m
; estimated printing time (silent mode) = 1m
G90
M82
T0
G92 E0
G1 X10 Y10 E1
G1 E0.5 ; retract
G1 X20 E2 ; unretract and extrude
;LAYER_CHANGE
T1
T1
M82
G92 E0
G1 X30 E1.5
;LAYER_CHANGE
T0
G92 E0
G1 E1
