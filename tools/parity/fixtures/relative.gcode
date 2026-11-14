; CONFIG_BLOCK_START
; gcode_flavor = klipper
; layer_height = 0.2
; nozzle_temperature = 210
; CONFIG_BLOCK_END
G90
M82
G92 E10
G1 E11
G91
M82
G1 E1
M83
G90
G92 E10
G1 X10 E1
G1 E-0.5 ; retract
G1 X20 E1.5
