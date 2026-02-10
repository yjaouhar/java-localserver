import sys

data = sys.stdin.read()
print("Content-Type: text/plain\n")
print("Hello CGI\n")
print("Body: "+ data +"b\n")
