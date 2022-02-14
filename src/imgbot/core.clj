(ns imgbot.core
  (:gen-class)
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [clojure.tools.cli :refer [parse-opts]]
            [babashka.process :refer [check $]]))

(defn expand-home [s]
  (if (.startsWith s "~")
    (clojure.string/replace-first s "~" (System/getProperty "user.home"))
    s))

(defn fname-heic->png [file]
  (str/replace file #"HEIC|heic" "png"))

(defn str->int [e]
  (Integer/parseInt e))

(defn size-kb [file]
  (-> ($ bash -c ~(str "du -k '" file  "' | cut  -f1")) deref check :out slurp str/trim str->int))

(defn size-mb [file]
   (/ (size-kb file) 1000.0))

(defn with-timeout [timeout-ms callback]
  (try
    (let [fut (future (callback))
          _ (print "Calling the command in a future")
          ret (deref fut timeout-ms ::timed-out)]
      (when (= ret ::timed-out)
        (future-cancel fut))
      ret)
    (catch Exception _
      (print "Caught ya!")
      ::timed-out)))

(defn with-retry [{:keys [retries timeout operation id] :as d}]
  (loop [try 1
         acc (assoc d :aborted false :retries 0 :res nil)]
    (if (= (dec try) retries)
      (assoc acc :aborted true)
      (do
        (println "Starting command with timeout " timeout " -- " id)
        (let [r (with-timeout timeout operation)]
          (if (not= r ::timed-out)
            (do
              (println "Command succeeded")
              (assoc acc :res r))
            (do
              (println "Command failed")
              (recur (inc try)
                     (assoc acc :retries try)))))))))


(defn resize-png [file max-size-mb]
  (loop [size (size-mb file)]
    (println file " ==> " size)
    (if (< size max-size-mb)
      file
      (do
        (with-retry
          {:retries 3
           :timeout 8000
           :id "Resizing image"
           :operation #(-> @($ mogrify -resize "50%" ~file) check :out slurp str/trim)})
        (recur (size-mb file))))))

(defn heic->png! [file]
  (with-retry
    {:retries 3
     :timeout 8000
     :id ["mogrify" "-format" "png" file]
     :operation #(-> @($ mogrify -format png ~file) check :out slurp str/trim)}))

(defn process-file
  [file landing-zone backup-zone max-size-mb]
   (println "Processing: " file)
   (cond
     (not (fs/exists? file)) nil

     (or (str/ends-with? file "HEIC") (str/ends-with? file "heic"))
     (let [res (heic->png! file)]
       (println res)
       (when (= false (:aborted res))
         (fs/move file backup-zone {:replace-existing true})))


     (or (str/ends-with? file "png") (str/ends-with? file "PNG"))
     (do
       (fs/copy file backup-zone {:replace-existing true})
       (resize-png file max-size-mb)
       (fs/move file landing-zone {:replace-existing true}))

     :else
     (throw (Exception. (str "File cannot be handled" file))))
   (println "Done processing: " file))


(def cli-options
  [["-i" "--input-folder FOLDER" "Folder containing the images"
    :default (expand-home "~/Downloads")]
   ["-b" "--backup-folder FOLDER" "Folder to move the original images to after processing"
    :default (expand-home "~/Downloads/backup")]
   ["-d" "--dest-folder FOLDER" "Folder to move the processed image to"
    :default (expand-home "~/Downloads/landing")]
   ["-s" "--mb-size SIZE" "Max size in MB of the processed images"
    :default 3]
   ["-h" "--help"]])

(defn usage [opts]
  (when (:errors opts)
    (println (:errors opts)))
  (println (:summary opts))
  (System/exit 0))


(defn -main [& args]
  (let [opts (parse-opts args cli-options)]
    (when (or (some? (:errors opts))
              (:help (:options opts)))
      (usage opts))

    (println "Starting")
    (loop []
      (Thread/sleep 500)
      (let [image-files (map str (fs/glob (-> opts :options :input-folder) "*{.heic,HEIC,png}"))]
        (when-not (empty? image-files)
          (doall (map #(process-file
                        %
                        (-> opts :options :dest-folder)
                        (-> opts :options :backup-folder)
                        (-> opts :options :mb-size))
                      image-files))
        (recur))))))
