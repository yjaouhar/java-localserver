from Xlib import display
import time

d = display.Display()
root = d.screen().root

# Function to get current pointer position
def get_pointer():
    pointer = root.query_pointer()
    return pointer.root_x, pointer.root_y

# Set the total run duration (in seconds): 2 hours 30 minutes = 9000 seconds
run_duration = 6 * 60 * 60  # or simply: 9000
start_time = time.time()

while True:
    elapsed_time = time.time() - start_time
    if elapsed_time >= run_duration:
        break  # stop after 2h30m

    x, y = get_pointer()
    root.warp_pointer(x + 1, y)  # move right by 1 pixel
    d.sync()
    time.sleep(0.1)
    root.warp_pointer(x, y)  # move back
    d.sync()
    time.sleep(30)
# pip install xlib
