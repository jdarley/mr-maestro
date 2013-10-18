(ns exploud.asgard_new
  "## Integration with Asgard

   We integrate with a few parts of [Asgard](https://github.com/Netflix/asgard). It's all good, at least until we have to switch to Azure.

   Asgard introduces a few concepts on top of AWS:

   - __Application__ - An application is a simple construct which pulls together a name, owner and an email address to send notifications about it.
   - __Cluster__ - A cluster is the collection of Auto Scaling Groups which form an application.
   - __Stack__ - A stack is a way of extending the naming conventions which Asgard uses to allow many groupings. We use it in our case as a synonym for environment."
  (:require [cheshire.core :as json]
            [clj-time.format :as fmt]
            [clojure
             [set :as set]
             [string :as str]]
            [clojure.tools.logging :as log]
            [dire.core :refer [with-handler! with-precondition! with-post-hook!]]
            [environ.core :refer [env]]
            [exploud
             [http :as http]
             [store :as store]
             [tyranitar :as tyr]
             [util :as util]]
            [overtone.at-at :as at-at]))

;; # General def-age

;; The VPC ID we'll be deploying into.
(def vpc-id (env :service-vpc-id))

;; The date formatter we'll use to write out our nicely parsed dates.
(def date-formatter (fmt/formatters :date-time-no-ms))

;; The formatter used by Asgard in log messages.
(def asgard-log-date-formatter (fmt/formatter "YYYY-MM-dd_HH:mm:ss"))

;; The formatter used by Asgard in its `updateTime` field. Why would you want to use the same format in two places?
(def asgard-update-time-formatter (fmt/formatter "YYYY-MM-dd HH:mm:ss z"))

;; The number of seconds we'll keep tracking a task for, before giving up.
(def task-track-count (* 1 60 60))

;; The default SSH key we'll use on the instances that are launched. This key will be replaced by our usual security measures so isn't really useful.
(def default-key-name "nprosser-key")

;; A set of the all parameters we can provide to Asgard when creating a new ASG.
(def all-create-new-asg-keys
  #{"_action_save"
    "appName"
    "appWithClusterOptLevel"
    "azRebalance"
    "countries"
    "defaultCooldown"
    "desiredCapacity"
    "detail"
    "devPhase"
    "hardware"
    "healthCheckGracePeriod"
    "healthCheckType"
    "iamInstanceProfile"
    "imageId"
    "instanceType"
    "kernelId"
    "keyName"
    "max"
    "min"
    "newStack"
    "partners"
    "pricing"
    "ramdiskId"
    "requestedFromGui"
    "revision"
    "selectedLoadBalancers"
    "selectedSecurityGroups"
    "selectedZones"
    "stack"
    "subnetPurpose"
    "terminationPolicy"
    "ticket"})

;; A set of all the parameters we can provide to Asgard when creating the next ASG for an application.
(def all-create-next-asg-keys
  #{"_action_createNextGroup"
    "afterBootWait"
    "azRebalance"
    "defaultCooldown"
    "desiredCapacity"
    "healthCheckGracePeriod"
    "healthCheckType"
    "iamInstanceProfile"
    "imageId"
    "instanceType"
    "kernelId"
    "keyName"
    "max"
    "min"
    "name"
    "noOptionalDefaults"
    "pricing"
    "ramdiskId"
    "selectedLoadBalancers"
    "selectedSecurityGroups"
    "selectedZones"
    "subnetPurpose"
    "terminationPolicy"
    "ticket"
    "trafficAllowed"})

;; # Concerning the creation of default Asgard parameter maps
;;
;; When combining these parameters for a deployment we'll take the defaults and merge them with the users parameters, letting the user parameters override the defaults. We'll then overlay the protected parameters for the operation back over the top.

;; A map of the default parameters shared by both creating a new ASG and creating the next ASG.
(def default-shared-params
  {"azRebalance" "enabled"
   "defaultCooldown" 10
   "desiredCapacity" 1
   "healthCheckGracePeriod" 600
   "healthCheckType" "EC2"
   "iamInstanceProfile" ""
   "instanceType" "t1.micro"
   "kernelId" ""
   "max" 1
   "min" 1
   "pricing" "ON_DEMAND"
   "ramdiskId" ""
   "selectedLoadBalancers" nil
   "selectedSecurityGroups" nil
   "selectedZones" ["a" "b"]
   "subnetPurpose" "internal"
   "terminationPolicy" "Default"})

;; A map of the default parameters we use when creating a new ASG so the user doesn't always have to provide everything.
(def default-create-new-asg-params
  (merge default-shared-params
         {"appWithClusterOptLevel" false
          "countries" ""
          "detail" ""
          "devPhase" ""
          "hardware" ""
          "newStack" ""
          "partners" ""
          "requestedFromGui" true
          "revision" ""}))

(defn protected-create-new-asg-params
  "Creates a map of the parameters we populate ourselves and won't let the user override when creating a new ASG."
  [application-name environment image-id ticket-id]
  {"_action_save" ""
   "appName" application-name
   "imageId" image-id
   "keyName" default-key-name
   "stack" environment
   "ticket" ticket-id})

;; A map of the default parameters we use when creating the next ASG for an application so the user doesn't always have to provide everything.
(def default-create-next-asg-params
  (merge default-shared-params
         {"afterBootWait" 30
          "noOptionalDefaults" true}))

(defn protected-create-next-asg-params
  "Creates a map of the parameters we populate ourselves and won't let the user override when creating the next ASG for an application."
  [application-name environment image-id ticket-id]
  {"_action_createNextGroup" ""
   "imageId" image-id
   "keyName" default-key-name
   "name" (str application-name "-" environment)
   "ticket" ticket-id
   "trafficAllowed" "off"})

;; # Concerning Asgard URL generation

;; The URL where Asgard is deployed.
(def asgard-url
  (env :service-asgard-url))

(defn- application-url
  "Gives us a region-based URL we can use to get information about an application."
  [region application-name]
  (str asgard-url "/" region "/cluster/show/" application-name ".json"))

(defn- auto-scaling-group-url
  "Gives us a region-based URL we can use to get information about an Auto Scaling Group."
  [region asg-name]
  (str asgard-url "/" region "/autoScaling/show/" asg-name ".json"))

(defn- auto-scaling-save-url
  "Gives us a region-based URL we can use to save Auto Scaling Groups."
  [region]
  (str asgard-url "/" region "/autoScaling/save"))

(defn- cluster-create-next-group-url
  "Gives us a region-based URL we can use to create the next Auto Scaling Group."
  [region]
  (str asgard-url "/" region "/cluster/createNextGroup"))

(defn- cluster-index-url
  "Gives us a region-based URL we can use to make changes to Auto Scaling Groups."
  [region]
  (str asgard-url "/" region "/cluster/index"))

(defn- security-groups-list-url
  "Gives us a region-based URL we can use to get a list of all Security Groups."
  [region]
  (str asgard-url "/" region "/security/list.json"))

(defn- tasks-url
  "Gives us a region-based URL we can use to get tasks."
  [region]
  (str asgard-url "/" region "/task/list.json"))

(defn- task-by-id-url
  "Gives us a region-based URL we can use to get a single task by its ID."
  [region task-id]
  (str asgard-url "/" region "/task/show/" task-id ".json"))

;; # Task transformations

(defn- split-log-message
  "Splits a log message from its Asgard form (where each line is something like `2013-10-11_18:25:23 Completed in 1s.`) to a map with separate `:date` and `:message` fields."
  [log]
  (let [[date message] (clojure.string/split log #" " 2)]
    {:date (fmt/unparse date-formatter (fmt/parse asgard-log-date-formatter date)) :message message}))

(defn- correct-date-time
  "Parses a task date/time from its Asgard form (like `2013-10-11 14:20:42 UTC`) to an ISO8601 one. Unfortunately has to do a crappy string-replace of `UTC` for `GMT`, ugh..."
  [date]
  (fmt/unparse date-formatter (fmt/parse asgard-update-time-formatter (str/replace date "UTC" "GMT"))))

(defn- munge-task
  "Converts an Asgard task to a desired form by using `split-log-message` on each line of the `:log` in the task and replacing it."
  [{:keys [log updateTime] :as task}]
  (cond log
        (assoc-in task [:log] (map split-log-message log))
        updateTime
        (assoc-in task [:updateTime] (correct-date-time updateTime))))

;; # Concerning grabbing objects from Asgard

(defn auto-scaling-group
  "Retrieves information about an Auto Scaling Group from Asgard."
  [region asg-name]
  (let [{:keys [status body]} (http/simple-get (auto-scaling-group-url region asg-name))]
    (when (= status 200)
      (json/parse-string body true))))

(defn last-auto-scaling-group
  "Retrieves the last ASG for an application, or `nil` if one doesn't exist."
  [region application-name]
  (let [{:keys [status body]} (http/simple-get (application-url region application-name))]
    (when (= status 200)
      (last (json/parse-string body true)))))

(defn security-groups
  "Retrives all security groups within a particular region."
  [region]
  (let [{:keys [body status]} (http/simple-get (security-groups-list-url region))]
    (when (= status 200)
      (:securityGroups (json/parse-string body true)))))

(defn task-by-url
  "Retrieves a task by its URL."
  [task-url]
  (let [{:keys [body status]} (http/simple-get task-url)]
    (when (= status 200)
      (munge-task (json/parse-string body true)))))

(defn tasks
  "Retrieves all tasks that Asgard knows about. It will combine `:runningTaskList` with `:completedTaskList` (with the running tasks first in the list)."
  [region]
  (let [{:keys [body status]} (http/simple-get (tasks-url region))]
    (when (= status 200)
      (let [both (json/parse-string)]
        (concat (:runningTaskList both) (:completedTaskList both))))))

;; # Concerning parameter transformation

(defn replace-load-balancer-key
  "If `subnetPurpose` is `internal` and `selectedLoadBalancers` is found within `params` the key name will be switched with `selectedLoadBalancersForVpcId{vpc-id}"
  [params]
  (if (= "internal" (get params "subnetPurpose"))
    (set/rename-keys params {"selectedLoadBalancers" (str "selectedLoadBalancersForVpcId" vpc-id)})
    params))

(defn is-security-group-id?
  "Whether `security-group` starts with `sg-`"
  [security-group]
  (re-find #"^sg-" security-group))

(defn get-security-group-id
  "Gets the ID of a security group with the given name in a particular region."
  [security-group region]
  (let [security-groups (security-groups region)]
    (if-let [found-group (first (filter (fn [sg] (= security-group (:groupName sg))) security-groups))]
      (:groupId found-group)
      (throw (ex-info "Unknown security group name" {:type ::unknown-security-group
                                                     :name security-group
                                                     :region region})))))

(defn replace-security-group-name
  "If `security-group` looks like it's a security name, it'll be switched with its ID."
  [region security-group]
  (if (is-security-group-id? security-group)
    security-group
    (get-security-group-id security-group region)))

(defn replace-security-group-names
  "If `subnetPurpose` is `internal` and `securityGroupNames` is found within `params` the value will be checked for security group names and replaced with their IDs (since we can't use security group names in a VPC."
  [params region]
  (if (= "internal" (get params "subnetPurpose"))
    (if-let [security-group-names (util/list-from (get params "selectedSecurityGroups"))]
      (let [security-group-ids (map (fn [sg] (replace-security-group-name region sg))
                                    security-group-names)]
        (assoc params "selectedSecurityGroups" security-group-ids))
      params)
    params))

(defn prepare-params
  "Prepares Asgard parameters by running them through a series of transformations."
  [params region]
  (-> params
      replace-load-balancer-key
      (replace-security-group-names region)))

(defn explode-params
  "Take a map of parameters and turns them into a list of [key value] pairs where the same key may appear multiple times. This is used to create the form parameters which we pass to Asgard (and may be specified multiple times each)."
  [params]
  (for [[k v] (seq params)
        vs (flatten (conj [] v))]
    [k vs]))

;; # Concerning tracking tasks

;; A pool which we use to refresh the tasks from Asgard.
(def task-pool (at-at/mk-pool))

;; The states at which a task is deemed finished.
(def finished-states #{"completed" "failed" "terminated"})

(defn finished?
  "Indicates whether the task is completed."
  [task]
  (contains? finished-states (:status task)))

;; We're going to need this in a minute.
(declare track-task)

(defn track-until-completed
  "After a 1s delay, tracks the task by saving its content to the task store until it is completed (as indicated by `finished?`) or `count` reaches 0."
  [ticket-id {:keys [url] :as task} count]
  (at-at/after 1000 #(track-task ticket-id task count) task-pool :desc url))

(defn track-task
  "Grabs the task by its URL and updates its details in the store. If it's not completed and we've not exhausted the retry count it'll reschedule itself."
  [ticket-id {:keys [url] :as task} count]
  (when-let [asgard-task (task-by-url url)]
    (store/store-task (merge task asgard-task))
    (when (and (not (finished? asgard-task))
               (pos? count))
      (track-until-completed ticket-id task (dec count)))))

;; Handler for recovering from failure while tracking a task. In the event of an exception marked with a `:class` of `:http` or `:store` we'll reschedule.
(with-handler! #'track-task
  clojure.lang.ExceptionInfo
  (fn [e ticket-id task count]
    (let [data (.getData e)]
      (if (or (= (:class data) :http)
              (= (:class data) :store))
        (track-until-completed ticket-id task (dec count))
        (throw e)))))

;; # Concerning deleting ASGs

(defn delete-asg
  "Begins a delete operation on the specified Auto Scaling Group in the region given. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region asg-name ticket-id task]
  (let [params {"_action_delete" ""
                "name" asg-name
                "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params params})]
    (if (= status 302)
      (track-until-completed ticket-id (merge task {:url (str (get headers "location") ".json")}) task-track-count)
      (throw (ex-info "Unexpected status while deleting ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `delete-asg` asserting that the ASG we're attempting to delete exists.
(with-precondition! #'delete-asg
  :asg-exists
  (fn [r a _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `delete-asg`.
(with-handler! #'delete-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning resizing ASGs

(defn resize-asg
  "Begins a resize operation on the specified Auto Scaling Group in the region given. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region asg-name ticket-id task new-size]
  (let [params {"_action_resize" ""
                "minAndMaxSize" new-size
                "name" asg-name
                "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params params})]
    (if (= status 302)
      (track-until-completed ticket-id (merge task {:url (str (get headers "location") ".json")}) task-track-count)
      (throw (ex-info "Unexpected status while resizing ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `resize-asg` asserting that the ASG we're attempting to resize exists.
(with-precondition! #'resize-asg
  :asg-exists
  (fn [r a _ _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `resize-asg`.
(with-handler! #'resize-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning enabling traffic for ASGs

(defn enable-asg
  "Begins an enable traffic operation on the specified Auto Scaling Group in the region given. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region asg-name ticket-id task]
  (let [params {"_action_activate" ""
                "name" asg-name
                "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params params})]
    (if (= status 302)
      (track-until-completed ticket-id (merge task {:url (str (get headers "location") ".json")}) task-track-count)
      (throw (ex-info "Unexpected status while enabling ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `enable-asg` asserting that the ASG we're attempting to enable exists.
(with-precondition! #'enable-asg
  :asg-exists
  (fn [r a _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `enable-asg`.
(with-handler! #'enable-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning disabling traffic for ASGs

(defn disable-asg
  "Begins an disable traffic operation on the specified Auto Scaling Group in the region given. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region asg-name ticket-id task]
  (let [params {"_action_deactivate" ""
                "name" asg-name
                "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params params})]
    (if (= status 302)
      (track-until-completed ticket-id (merge task {:url (str (get headers "location") ".json")}) task-track-count)
      (throw (ex-info "Unexpected status while disabling ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `disable-asg` asserting that the ASG we're attempting to disable exists.
(with-precondition! #'disable-asg
  :asg-exists
  (fn [r a _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `disable-asg`.
(with-handler! #'disable-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning the creation of the first ASG for an application

(defn extract-new-asg-name
  "Extracts the new ASG name from what would be the `Location` header of the response when asking Asgard to create a new ASG. Takes something like `http://asgard/{region}/autoScaling/show/{asg-name}` and gives back `{asg-name}`."
  [url]
  (second (re-find #"/autoScaling/show/(.+)$" url)))

(defn create-new-asg-asgard-params
  "Creates the Asgard parameters for creating a new ASG as a combination of the various defaults and user-provided parameters."
  [region application-name environment image-id commit-hash ticket-id]
  (let [protected-params (protected-create-new-asg-params application-name environment image-id ticket-id)
        user-params (tyr/deployment-params environment application-name commit-hash)]
    (prepare-params (merge default-create-new-asg-params user-params protected-params) region)))

(defn create-new-asg
  "Begins a create new Auto Scaling Group operation for the specified application and environment in the region given. It __WILL__ start traffic to the newly-created ASG. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region application-name environment image-id commit-hash ticket-id task]
  (let [asgard-params (create-new-asg-asgard-params region application-name environment image-id commit-hash ticket-id)
        {:keys [status headers] :as response} (http/simple-post
                                               (auto-scaling-save-url region)
                                               {:form-params (explode-params asgard-params)})]
    (if (= status 302)
      (let [new-asg-name (extract-new-asg-name (get headers "location"))
            tasks (tasks region)]
        (when-let [found-task (first (filter (fn [t] (= (:name t) (str "Create Auto Scaling Group '" new-asg-name "'"))) tasks))]
          (let [task-id (:id found-task)
                url (task-by-id-url region task-id)]
            (track-until-completed ticket-id (merge task {:url url}) task-track-count)))
        new-asg-name)
      (throw (ex-info "Unexpected status while creating new ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; # Concerning the creation of the next ASG for an application-name

(defn create-next-asg-asgard-params
  "Creates the Asgard parameters for creating the next ASG as a combination of the various defaults and user-provided parameters."
  [region application-name environment image-id commit-hash ticket-id]
  (let [protected-params (protected-create-next-asg-params application-name environment image-id ticket-id)
        user-params (tyr/deployment-params environment application-name commit-hash)]
    (prepare-params (merge default-create-next-asg-params user-params protected-params) region)))

(defn create-next-asg
  "Begins a create next Auto Scaling Group operation for the specified application and environment in the region given. It __WILL NOT__ start traffic to the newly-created ASG. Will start tracking the resulting task URL until completed. You can assume that a non-explosive call has been successful and the task is being tracked."
  [region application-name environment image-id commit-hash ticket-id task]
  (let [asgard-params (create-next-asg-asgard-params region application-name environment image-id commit-hash ticket-id)
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-create-next-group-url region)
                                               {:form-params (explode-params asgard-params)})]
    (if (= status 302)
      (track-until-completed ticket-id (merge task {:url (str (get headers "location") ".json")}) task-track-count)
      (throw (ex-info "Unexpected status while creating next ASG"
                      {:type ::unexpected-response
                       :response response})))))
