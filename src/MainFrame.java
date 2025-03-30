import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.UIManager;

public class MainFrame {
    private JFrame frame;
    private static final String DIR_PATH = "files";
    private final AtomicBoolean stopFlag;
    private final int producerCount;
    private final int consumerCount;
    private final ExecutorService executor;
    private final List<Future<?>> producerFutures;
    private final long interval = 15;

    /**
     * Główna metoda uruchamiająca aplikację.
     */
    public static void main(String[] args) {
        setLookAndFeel();
        EventQueue.invokeLater(() -> {
            try {
                MainFrame window = new MainFrame();
                window.frame.pack();
                window.frame.setAlwaysOnTop(true);
                window.frame.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Konstruktor klasy MainFrame. Inicjalizuje flagę stopu, liczbę producentów i konsumentów oraz executor.
     */
    public MainFrame() {
        stopFlag = new AtomicBoolean(false);
        producerCount = 1;
        consumerCount = 2;
        executor = Executors.newFixedThreadPool(producerCount + consumerCount);
        producerFutures = new ArrayList<>();
        initialize();
    }

    /**
     * Ustawia wygląd i styl aplikacji na systemowy.
     */
    private static void setLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Inicjalizuje główne okno aplikacji oraz dodaje obsługę zamykania okna.
     */
    private void initialize() {
        frame = new JFrame();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                executor.shutdownNow();
            }
        });
        frame.setBounds(100, 100, 450, 300);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel();
        frame.getContentPane().add(panel, BorderLayout.NORTH);
        addButtons(panel);
    }

    /**
     * Dodaje przyciski do panelu sterowania.
     * @param panel Panel, do którego dodawane są przyciski.
     */
    private void addButtons(JPanel panel) {
        JButton btnStart = new JButton("Start");
        btnStart.addActionListener(e -> startMultiThreadedStatistics());
        JButton btnStop = new JButton("Stop");
        btnStop.addActionListener(e -> stopAllTasks());
        JButton btnClose = new JButton("Close");
        btnClose.addActionListener(e -> closeApplication());

        panel.add(btnStart);
        panel.add(btnStop);
        panel.add(btnClose);
    }

    /**
     * Rozpoczyna wielowątkowe przetwarzanie statystyk.
     */
    private void startMultiThreadedStatistics() {
        if (isAnyProducerRunning()) {
            JOptionPane.showMessageDialog(frame, "Cannot start a new task! At least one producer is still running!", "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        stopFlag.set(false);
        producerFutures.clear();

        BlockingQueue<Optional<Path>> queue = new LinkedBlockingQueue<>(consumerCount);

        for (int i = 0; i < producerCount; i++) {
            Future<?> future = executor.submit(createProducer(queue));
            producerFutures.add(future);
        }

        for (int i = 0; i < consumerCount; i++) {
            executor.execute(createConsumer(queue));
        }
    }

    /**
     * Sprawdza, czy którykolwiek z producentów jest wciąż uruchomiony.
     * @return true, jeśli przynajmniej jeden producent działa, w przeciwnym razie false.
     */
    private boolean isAnyProducerRunning() {
        for (Future<?> future : producerFutures) {
            if (!future.isDone()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tworzy zadanie producenta, które przetwarza pliki i dodaje je do kolejki.
     * @param queue Kolejka, do której dodawane są pliki.
     * @return Runnable reprezentujący zadanie producenta.
     */
    private Runnable createProducer(BlockingQueue<Optional<Path>> queue) {
        return () -> {
            String threadName = Thread.currentThread().getName();
            System.out.println("PRODUCER " + threadName + " STARTED...");

            while (!Thread.currentThread().isInterrupted()) {
                if (stopFlag.get()) {
                    addPoisonPills(queue);
                    break;
                } else {
                    processFiles(queue);
                }
                sleepInterval(threadName);
            }
            System.out.println("PRODUCER " + threadName + " FINISHED");
        };
    }

    /**
     * Dodaje "trucizny" do kolejki, aby zakończyć pracę konsumentów.
     * @param queue Kolejka, do której dodawane są "trucizny".
     */
    private void addPoisonPills(BlockingQueue<Optional<Path>> queue) {
        for (int i = 0; i < consumerCount; i++) {
            try {
                queue.put(Optional.empty());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Przetwarza pliki w katalogu i dodaje je do kolejki.
     * @param queue Kolejka, do której dodawane są pliki.
     */
    private void processFiles(BlockingQueue<Optional<Path>> queue) {
        try {
            Files.walkFileTree(Paths.get(DIR_PATH), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".txt")) {
                        try {
                            queue.put(Optional.of(file));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Uśpienie wątku na określony czas.
     * @param threadName Nazwa wątku, który jest uśpiony.
     */
    private void sleepInterval(String threadName) {
        System.out.printf("Producer %s will check directories again in %d seconds%n", threadName, interval);
        try {
            TimeUnit.SECONDS.sleep(interval);
        } catch (InterruptedException e) {
            System.out.printf("Producer %s sleep interrupted!%n", threadName);
            if (!stopFlag.get()) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Tworzy zadanie konsumenta, które przetwarza pliki z kolejki.
     * @param queue Kolejka, z której pobierane są pliki.
     * @return Runnable reprezentujący zadanie konsumenta.
     */
    private Runnable createConsumer(BlockingQueue<Optional<Path>> queue) {
        return () -> {
            String threadName = Thread.currentThread().getName();
            System.out.println("CONSUMER " + threadName + " STARTED...");

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Optional<Path> optPath = queue.take();
                    if (optPath.isPresent()) {
                        Map<String, Long> wordStats = getLinkedCountedWords(optPath.get());
                        System.out.println("Most frequent words: " + wordStats);
                    } else {
                        break;
                    }
                } catch (InterruptedException e) {
                    System.out.printf("Consumer %s waiting for new element from queue interrupted!%n", threadName);
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("CONSUMER " + threadName + " FINISHED");
        };
    }

    /**
     * Zlicza wystąpienia słów w pliku i zwraca 10 najczęściej występujących słów.
     * @param path Ścieżka do pliku.
     * @return Mapa z 10 najczęściej występującymi słowami i ich liczbą wystąpień.
     */
    private Map<String, Long> getLinkedCountedWords(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            return reader.lines()
                    .flatMap(line -> Stream.of(line.split("\\s+")))
                    .map(word -> word.replaceAll("[^a-zA-Z0-9ąęóśćżńźĄĘÓŚĆŻŃŹ]", ""))
                    .filter(word -> word.length() > 2)
                    .map(String::toLowerCase)
                    .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .limit(10)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, v) -> v, LinkedHashMap::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Zatrzymuje wszystkie zadania producentów i konsumentów.
     */
    private void stopAllTasks() {
        stopFlag.set(true);
        for (Future<?> future : producerFutures) {
            future.cancel(true);
        }
    }

    /**
     * Zamyka aplikację i zwalnia zasoby.
     */
    private void closeApplication() {
        executor.shutdownNow();
        frame.dispose();
    }
}