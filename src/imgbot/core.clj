(ns imgbot.core
  (:gen-class)
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [babashka.process :refer [process check $]]))

(def size-mb-threshold 3)
(def landing-zone "/Users/laurent/Downloads/landing")
(def backup-zone "/Users/laurent/Downloads/backup")

(defn fname-heic->png [file]
  (str/replace (str/replace file "HEIC" "png") "heic" "png"))

(defn str->int [e]
  (Integer/parseInt e))

(defn size-kb [file]
  (-> (process ["bash" "-c" (str "du -k " file  " | cut  -f1")] {:out :string}) check :out str/trim str->int))

(defn size-mb [file]
   (/ (size-kb file) 1000.0))

(defn resize-png [file max-size-mb]
  (loop [size (size-mb file)]
    (println file " ==> " size)
    (if (< size max-size-mb)
      file
      (do
        (-> (process ["mogrify" "-resize" "50%" file]) check)
        (recur (size-mb file))))))

(defn heic->png! [file]
  (-> (process ["mogrify" "-format" "png" file] {:out :string}) check))

(defn process-file
  ([file]
   (process-file file
                 size-mb-threshold
                 landing-zone
                 backup-zone))
  ([file max-size-mb landing-zone backup-zone]
   (println "Processing: " file)
   (when-not (fs/exists? file)
     (throw (Exception. (str "File does not exist " file))))
   (cond
     (or (str/ends-with? file "HEIC")
         (str/ends-with? file "heic"))
     (do
       (heic->png! file)
       (fs/move file backup-zone {:replace-existing true})
       (process-file (fname-heic->png file) size-mb-threshold landing-zone backup-zone))

     (or (str/ends-with? file "png")
         (str/ends-with? file "PNG"))
     (do
       (fs/copy file backup-zone {:replace-existing true})
       (resize-png file max-size-mb)
       (fs/move file landing-zone {:replace-existing true}))

     :else
     (throw (Exception. (str "File cannot be handled" file))))))

(def run? (atom true))
(defn main []
  (future
    (Thread/sleep 500)
    (when @run?
      (println
       (let [heic-files (map str (fs/glob "/Users/laurent/Downloads" "*{.heic,HEIC,png}"))]
         (map process-file heic-files)))
      (recur))))

(reset! run? true)

(main)
