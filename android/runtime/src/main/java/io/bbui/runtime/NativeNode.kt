package io.bbui.runtime

internal object NativeNode {
  init {
    System.loadLibrary("node")
    System.loadLibrary("bbui_node")
  }
  external fun createPipes(): IntArray
  external fun run(arguments: Array<String>, home: String, temp: String, workingDir: String): Int
}
