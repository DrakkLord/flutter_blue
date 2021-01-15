class FlutterBlueError extends Error {}

class FatalConnectionError extends FlutterBlueError {
  final Exception errorReason;
  FatalConnectionError(this.errorReason);
}
