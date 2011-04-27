(ns swank-clj.connection
  (:require
   [swank-clj.logging :as logging]
   [clojure.java.io :as java-io])
  (:import
   java.io.BufferedReader
   java.io.FileReader
   java.io.InputStreamReader
   java.io.OutputStreamWriter
   java.io.PrintWriter
   java.io.StringWriter))


(defn connected?
  "Predicate to test if connection is open"
  [connection]
  (let [connection @connection]
    ((:connected? connection) connection)))

(defn close
  "Close a connection"
  [connection]
  (let [connection @connection]
    ((:close-connection connection) connection)))

(defn send-to-emacs*
  "Sends a message (msg) to emacs."
  [connection msg]
  ((:write-message connection) connection msg))

(defn send-to-emacs
  "Sends a message (msg) to emacs."
  [connection msg]
  (send-to-emacs* @connection msg))

(defn read-from-connection
  "Read a form from the connection."
  [connection]
  (let [connection @connection]
    ((:read-message connection) connection)))

(defn write-to-input
  "Read a form from the connection."
  [connection tag value]
  (let [connection @connection]
    (if (= tag @(:input-tag connection))
      (do
        (reset! (:input-tag connection) nil)
        (.write (:input-source connection) (.getBytes value) 0 (.length value)))
      (logging/trace
       "Input with tag mismatch %s %s" tag @(:input-tag connection)))))


(defn ^PrintWriter call-on-flush-stream
  "Creates a stream that will call a given function when flushed."
  [flushf]
  (let [closed? (atom false)]
    (PrintWriter.
     (proxy [StringWriter] []
       (close [] (reset! closed? true))
       (flush []
              (let [#^StringWriter me this
                    len (.. me getBuffer length)]
                (when (> len 0)
                  (flushf (.. me getBuffer (substring 0 len)))
                  (.. me getBuffer (delete 0 len))))))
     true)))

(defn- ^java.io.StringWriter make-output-redirection
  ([io-connection]
     (call-on-flush-stream
      #((:write-message io-connection) io-connection `(:write-string ~%)))))


(def tag-counter (atom 0))
(defn make-tag []
  (swap! tag-counter (fn [x] (mod (inc x) Long/MAX_VALUE))))

(defn thread-id
  ([] (thread-id (Thread/currentThread)))
  ([#^Thread thread]
     (.getId thread)))

(defn ^java.io.Reader make-repl-input-stream
  "Creates a stream that will ask emacs for input."
  [connection]
  (logging/trace "make-repl-input-stream")
  (let [out-to-in (java.io.PipedOutputStream.)
        request-pending (atom nil)
        request-input (fn [] (let [tag (make-tag)]
                               (when (compare-and-set! request-pending nil tag)
                                 ;; (logging/trace
                                 ;;  "make-repl-input-stream: requesting..")
                                 (send-to-emacs*
                                  connection
                                  `(:read-string ~(thread-id) ~tag)))))
        in (proxy [java.io.PipedInputStream] [out-to-in]
             (read ([]
                      ;; (logging/trace "make-repl-input-stream: read")
                      ;; (when (zero? (.available this))
                      ;;   (request-input))
                      (proxy-super read))
                   ([b s l]
                      (logging/trace "make-repl-input-stream: read 3")
                      (when (zero? (.available this))
                        (request-input))
                      (proxy-super read b s l))))]
    [(java.io.PushbackReader. (java.io.InputStreamReader. in))
     out-to-in request-pending]))


(defn- initialise
  "Set up the initial state of an accepted connection."
  [io-connection options]
  (let [connection (doto
                       (atom
                        (merge
                         options
                         io-connection
                         {:sldb-levels []
                          :pending #{}
                          :timeout nil
                          :writer-redir (make-output-redirection
                                         io-connection)
                          :inspector (atom {})
                          :result-history nil
                          :last-exception nil})))]
    ;;(when-not (:proxy-to options))

      (swap! connection
             (fn [connection]
               (merge connection
                      (zipmap
                       [:input-redir :input-source :input-tag]
                       (make-repl-input-stream connection)))))
    ;; (logging/trace "connection %s" (pr-str @connection))
    connection))

(defn add-pending-id [connection id]
  (swap! connection update-in [:pending] conj id))

(defn remove-pending-id [connection id]
  (swap! connection update-in [:pending] disj id))

(defn pending
  [connection]
  (:pending @connection))

(defn close-connection
  [connection]
  (logging/trace "close-connection")
  ((:close-connection @connection) @connection)
  (swap! connection dissoc :read-message :reader :write-message :writer))

(def ^{:private true}
  slime-secret-path
  (.getPath (java-io/file (System/getProperty "user.home") ".slime-secret")))

(defn- slime-secret
  "Returns the first line from the slime-secret file, path found in
   slime-secret-path (default: .slime-secret in the user's home
   directory)."
  ([] (try
        (let [file (java-io/file slime-secret-path)]
          (when (and (.isFile file) (.canRead file))
            (with-open [secret (BufferedReader. (FileReader. file))]
              (.readLine secret)))))))

(defn- authenticate
  "Authenticate a new connection.

   Authentication depends on the contents of a slime-secret file on
   both the server (swank) and the client (emacs slime). If no
   slime-secret file is provided on the server side, all connections
   are accepted.

   See also: `slime-secret'"
  [connection]
  (if-let [secret (slime-secret)]
    (when-not (= (read-from-connection connection) secret)
      (logging/trace "authenticate: closing connection")
      (close-connection connection)
      nil)
    connection))

(defn create
  [io-connection options]
  (authenticate (initialise io-connection options)))

(defn next-sldb-level
  [connection level-info]
  (logging/trace "next-sldb-level")
  (-> (swap!
       connection
       (fn [current]
         (->
          current
          (update-in [:sldb-levels]
                     (fn [levels]
                       (conj (or levels []) level-info)))
          (dissoc :abort-to-level))))
      :sldb-levels
      count))

(defn sldb-drop-level [connection n]
  (swap! connection update-in [:sldb-levels] subvec 0 n))

(defn sldb-level
  [connection]
  (count (:sldb-levels @connection)))

(defn sldb-level-info
  ([connection]
     (last (:sldb-levels @connection)))
  ([connection level]
     (nth (:sldb-levels @connection) (dec level))))

(defn aborting-level?
  "Aborting predicate."
  [connection]
  (let [connection @connection]
    (if-let [abort-to-level (:abort-to-level connection)]
      (>= (count (:sldb-levels connection)) abort-to-level))))

(defn inspector
  "Return the connection's inspector information."
  [connection]
  (:inspector @connection))

(defn swank-handler
  [connection]
  (:swank-handler @connection))

(defn add-result-to-history
  "Add result to history, returning a history vector"
  [connection result]
  (->
   (swap! connection update-in [:result-history]
          (fn [history]
            (take 3 (conj history result))))
   :result-history))

(defn connection-type
  [connection]
  (if (:proxy-to @connection)
    :proxy
    :repl))
