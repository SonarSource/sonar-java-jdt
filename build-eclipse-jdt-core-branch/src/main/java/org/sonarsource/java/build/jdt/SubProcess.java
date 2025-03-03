package org.sonarsource.java.build.jdt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SubProcess {

  private static final int DEFAULT_BUFFER_SIZE = 16384;

  public final byte[] stdout;
  public final byte[] stderr;
  public final int exitCode;

  private SubProcess(int exitCode, byte[] stdout, byte[] stderr) {
    this.exitCode = exitCode;
    this.stdout = stdout;
    this.stderr = stderr;
  }

  public SubProcess throwIfFailed(String message) throws IOException {
    if (failed()) {
      throw new IOException("[FAILED, exit code: " + exitCode + "]: " + message);
    }
    return this;
  }

  public boolean failed() {
    return exitCode != 0;
  }

  public boolean succeeded() {
    return exitCode == 0;
  }

  public String stdout() {
    return new String(stdout, UTF_8);
  }

  public String stderr() {
    return new String(stderr, UTF_8);
  }

  public static SubProcess exec(String... command) throws IOException, InterruptedException {
    return exec(Path.of("."), command);
  }

  public static SubProcess exec(Path currentDirectory, String... command) throws IOException, InterruptedException {
    return exec(true, currentDirectory, command);
  }

  public static SubProcess exec(boolean inheritIO, Path currentDirectory, String... command) throws IOException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(command)
      .directory(currentDirectory.toRealPath().toFile())
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectError(ProcessBuilder.Redirect.PIPE);

    Process process = processBuilder.start();
    try (var executor = Executors.newFixedThreadPool(2);
      InputStream inputStream = process.getInputStream();
      InputStream errorStream = process.getErrorStream()) {
      Future<byte[]> stdout = readStream(executor, inputStream, inheritIO ? System.out : null);
      Future<byte[]> stderr = readStream(executor, errorStream, inheritIO ? System.err : null);
      int exitCode = process.waitFor();
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IOException("Failed to terminate the IO threads");
      }
      try {
        return new SubProcess(exitCode, stdout.get(), stderr.get());
      } catch (ExecutionException e) {
        throw new IOException(e.getClass().getSimpleName() + ", failed to read process output: " + e.getMessage(), e);
      }
    }
  }

  private static Future<byte[]> readStream(ExecutorService executorService, InputStream inputStream, OutputStream outputStream) {
    return executorService.submit(() -> {
      ByteArrayOutputStream stdout = new ByteArrayOutputStream();
      byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
      int read;
      while ((read = inputStream.read(buffer)) >= 0) {
        stdout.write(buffer, 0, read);
        if (outputStream != null) {
          outputStream.write(buffer, 0, read);
        }
      }
      stdout.flush();
      if (outputStream != null) {
        outputStream.flush();
      }
      return stdout.toByteArray();
    });
  }

}
