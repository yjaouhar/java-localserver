Client connect
     ↓
isAcceptable
     ↓
onAccept
     ↓
Register OP_READ
     ↓
isReadable
     ↓
onRead
     ↓
Register OP_WRITE
     ↓
isWritable
     ↓
onWrite
     ↓
Close

Socket  →  ByteBuffer  →  Code
Code    →  ByteBuffer  →  Socket


    get   /route hhtp1.1 \r\n
   : HOst local 8080\r\n
    contenle : 77\r\n
    \r\n
    body:  fhgdlkg 
    

javac -d bin -sourcepath src src/Main.java && java -cp bin Main
