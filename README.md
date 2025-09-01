# .fit file fixer

Fixes GPS glitches in FIT file (for example caused by latest Wahoo problems with older devices.)

Filters out points that are too far from middle point of the exercies (using distance).
And filters out points where speed of change bigger than 30m/s (can be adjusted, look for `SPEED_LIMIT`)

# Running
`mvn exec:java -Dfit.file="path-to-fit-file"`


new file `NAME-new.fit` will be created
