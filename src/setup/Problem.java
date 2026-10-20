package setup;

final class Problem extends RuntimeException{
  static final String network= "Check that this computer is connected to the internet, then press \"Try again\": the setup continues from where it stopped.";
  static final String report= "Press \"Try again\" in a few minutes; if the same message comes back, copy this message and the log below it into a new issue at https://github.com/MarcoServetto/FearlessSetUp/issues.";
  Problem(String message){ super(message); }
}
