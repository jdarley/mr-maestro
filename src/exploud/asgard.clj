(ns exploud.asgard
  "## Integration with Asgard

   We integrate with a few parts of [Asgard](https://github.com/Netflix/asgard).
   It's all good, at least until we have to switch to Azure.

   Asgard introduces a few concepts on top of AWS:

   - __Application__ - An application is a simple construct which pulls together
     a name, owner and an email address to send notifications about it.
   - __Cluster__ - A cluster is the collection of Auto Scaling Groups which form
     an application.
   - __Stack__ - A stack is a way of extending the naming conventions which
     Asgard uses to allow many groupings. We use it in our case as a synonym for
     environment."
  (:require [cheshire.core :as json]
            [clj-time.format :as fmt]
            [clojure
             [set :as set]
             [string :as str]]
            [clojure.tools.logging :as log]
            [dire.core :refer [with-handler!
                               with-precondition!
                               with-post-hook!]]
            [environ.core :refer [env]]
            [exploud
             [http :as http]
             [store :as store]
             [util :as util]]
            [overtone.at-at :as at-at]))

;; # General def-age

(def vpc-id
  "The VPC ID we'll be deploying into."
  (env :service-vpc-id))

(def asgard-log-date-formatter
  "The formatter used by Asgard in log messages."
  (fmt/formatter "YYYY-MM-dd_HH:mm:ss"))

(def asgard-update-time-formatter
  "The formatter used by Asgard in its `updateTime` field. Why would you want to
   use the same format in two places?"
  (fmt/formatter "YYYY-MM-dd HH:mm:ss z"))

(def task-track-count
  "The number of seconds we'll keep tracking a task for, before giving up."
  (* 1 60 60))

(def default-key-name
  "The default SSH key we'll use on the instances that are launched. This key
   will be replaced by our usual security measures so isn't really useful."
  "nprosser-key")

(def all-create-new-asg-keys
  "A set of the all parameters we can provide to Asgard when creating a new
   ASG."
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

(def all-create-next-asg-keys
  "A set of all the parameters we can provide to Asgard when creating the next
   ASG for an application."
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
;; When combining these parameters for a deployment we'll take the defaults and
;; merge them with the users parameters, letting the user parameters override
;; the defaults. We'll then overlay the protected parameters for the operation
;; back over the top.

(def default-shared-parameters
  "A map of the default parameters shared by both creating a new ASG and
   creating the next ASG."
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

(def default-create-new-asg-parameters
  "A map of the default parameters we use when creating a new ASG so the user
   doesn't always have to provide everything."
  (merge default-shared-parameters
         {"appWithClusterOptLevel" false
          "countries" ""
          "detail" ""
          "devPhase" ""
          "hardware" ""
          "newStack" ""
          "partners" ""
          "requestedFromGui" true
          "revision" ""}))

(defn protected-create-new-asg-parameters
  "Creates a map of the parameters we populate ourselves and won't let the user
   override when creating a new ASG."
  [application-name environment image-id ticket-id]
  {"_action_save" ""
   "appName" application-name
   "imageId" image-id
   "keyName" default-key-name
   "stack" environment
   "ticket" ticket-id})

(def default-create-next-asg-parameters
  "A map of the default parameters we use when creating the next ASG for an
   application so the user doesn't always have to provide everything."
  (merge default-shared-parameters
         {"afterBootWait" 30
          "noOptionalDefaults" true}))

(defn protected-create-next-asg-parameters
  "Creates a map of the parameters we populate ourselves and won't let the user
   override when creating the next ASG for an application."
  [application-name environment image-id ticket-id]
  {"_action_createNextGroup" ""
   "imageId" image-id
   "keyName" default-key-name
   "name" (str application-name "-" environment)
   "ticket" ticket-id
   "trafficAllowed" "off"})

;; # Concerning Asgard URL generation

(def asgard-url
  "The URL where Asgard is deployed."
  (env :service-asgard-url))

(defn- application-url
  "Gives us a region-based URL we can use to get information about an
   application."
  [region application-name]
  (str asgard-url "/" region "/application/show/" application-name ".json"))

(defn- application-list-url
  "Gives us a URL we can use to retrieve the list of applications."
  []
  (str asgard-url "/application/list.json"))

(defn- auto-scaling-group-url
  "Gives us a region-based URL we can use to get information about an Auto
   Scaling Group."
  [region asg-name]
  (str asgard-url "/" region "/autoScaling/show/" asg-name ".json"))

(defn- auto-scaling-save-url
  "Gives us a region-based URL we can use to save Auto Scaling Groups."
  [region]
  (str asgard-url "/" region "/autoScaling/save"))

(defn- cluster-create-next-group-url
  "Gives us a region-based URL we can use to create the next Auto Scaling
   Group."
  [region]
  (str asgard-url "/" region "/cluster/createNextGroup"))

(defn- cluster-index-url
  "Gives us a region-based URL we can use to make changes to Auto Scaling
   Groups."
  [region]
  (str asgard-url "/" region "/cluster/index"))

(defn- security-groups-list-url
  "Gives us a region-based URL we can use to get a list of all Security
   Groups."
  [region]
  (str asgard-url "/" region "/security/list.json"))

(defn- tasks-url
  "Gives us a region-based URL we can use to get tasks."
  []
  (str asgard-url "/task/list.json"))

(defn- task-by-id-url
  "Gives us a region-based URL we can use to get a single task by its ID."
  [region task-id]
  (str asgard-url "/" region "/task/show/" task-id ".json"))

(defn- upsert-application-url
  "Gives us a URL we can use to upsert an application."
  []
  (str asgard-url "/application/index"))

;; # Task transformations

(defn- split-log-message
  "Splits a log message from its Asgard form (where each line is something like
   `2013-10-11_18:25:23 Completed in 1s.`) to a map with separate `:date` and
   `:message` fields."
  [log]
  (let [[date message] (clojure.string/split log #" " 2)]
    {:date (str (fmt/parse asgard-log-date-formatter date)) :message message}))

(defn- correct-date-time
  "Parses a task date/time from its Asgard form (like `2013-10-11 14:20:42 UTC`)
   to an ISO8601 one. Unfortunately has to do a crappy string-replace of `UTC`
   for `GMT`, ugh..."
  [date]
  (str (fmt/parse asgard-update-time-formatter (str/replace date "UTC" "GMT"))))

(defn- munge-task
  "Converts an Asgard task to a desired form by using `split-log-message` on
   each line of the `:log` in the task and replacing it."
  [{:keys [log updateTime] :as task}]
  (cond log
        (assoc-in task [:log] (map split-log-message log))
        updateTime
        (assoc-in task [:updateTime] (correct-date-time updateTime))))

;; # Concerning grabbing objects from Asgard

(defn application
  "Retrieves information about an application from Asgard."
  [region application-name]
  (let [{:keys [status body]} (http/simple-get (application-url
                                                region
                                                application-name))]
    (when (= status 200)
      (json/parse-string body true))))

(defn applications
  "Retrieves the list of applications from Asgard."
  []
  (let [{:keys [status body]} (http/simple-get (application-list-url))]
    (when (= status 200)
      (json/parse-string body true))))

(defn auto-scaling-group
  "Retrieves information about an Auto Scaling Group from Asgard."
  [region asg-name]
  (let [{:keys [status body]} (http/simple-get (auto-scaling-group-url
                                                region
                                                asg-name))]
    (when (= status 200)
      (json/parse-string body true))))

(defn last-auto-scaling-group
  "Retrieves the last ASG for an application, or `nil` if one doesn't exist."
  [region application-name]
  (last (:groups (application region application-name))))

(defn security-groups
  "Retrives all security groups within a particular region."
  [region]
  (let [{:keys [body status]} (http/simple-get (security-groups-list-url
                                                region))]
    (when (= status 200)
      (:securityGroups (json/parse-string body true)))))

(defn task-by-url
  "Retrieves a task by its URL."
  [task-url]
  (let [{:keys [body status]} (http/simple-get task-url)]
    (when (= status 200)
      (munge-task (json/parse-string body true)))))

(defn tasks
  "Retrieves all tasks that Asgard knows about. It will combine
   `:runningTaskList` with `:completedTaskList` (with the running tasks first in
   the list)."
  []
  (let [{:keys [body status]} (http/simple-get (tasks-url))]
    (when (= status 200)
      (let [both (json/parse-string body true)]
        (concat (:runningTaskList both)
                (:completedTaskList both))))))

;; # Concerning updating things in Asgard

(defn upsert-application
  "Updates the information on an application in Asgard. This function will
   replace an already existing application or create a new one."
  [application-name {:keys [description email owner]}]
  (http/simple-post (upsert-application-url)
                    {:form-params {:description description
                                   :email email
                                   :monitorBucketType "application"
                                   :name application-name
                                   :owner owner
                                   :ticket ""
                                   :type "Web Service"
                                   :_action_update ""}
                     :follow-redirects false}))

;; # Concerning parameter transformation

(defn replace-load-balancer-key
  "If `subnetPurpose` is `internal` and `selectedLoadBalancers` is found within
   `parameters` the key name will be switched with
   `selectedLoadBalancersForVpcId{vpc-id}"
  [parameters]
  (if (= "internal" (get parameters "subnetPurpose"))
    (set/rename-keys parameters {"selectedLoadBalancers"
                                 (str "selectedLoadBalancersForVpcId" vpc-id)})
    parameters))

(defn is-security-group-id?
  "Whether `security-group` starts with `sg-`"
  [security-group]
  (re-find #"^sg-" security-group))

(defn get-security-group-id
  "Gets the ID of a security group with the given name in a particular region."
  [security-group region]
  (let [security-groups (security-groups region)]
    (if-let [found-group (first (filter (fn [sg] (= security-group
                                                   (:groupName sg)))
                                        security-groups))]
      (:groupId found-group)
      (throw (ex-info "Unknown security group name"
                      {:type ::unknown-security-group
                       :name security-group
                       :region region})))))

(defn replace-security-group-name
  "If `security-group` looks like it's a security name, it'll be switched with
   its ID."
  [region security-group]
  (if (is-security-group-id? security-group)
    security-group
    (get-security-group-id security-group region)))

(defn replace-security-group-names
  "If `subnetPurpose` is `internal` and `securityGroupNames` is found within
   `parameters` the value will be checked for security group names and replaced
   with their IDs (since we can't use security group names in a VPC."
  [parameters region]
  (if (= "internal" (get parameters "subnetPurpose"))
    (if-let [security-group-names (util/list-from
                                   (get parameters "selectedSecurityGroups"))]
      (let [security-group-ids (map (fn [sg]
                                      (replace-security-group-name region sg))
                                    security-group-names)]
        (assoc parameters "selectedSecurityGroups" security-group-ids))
      parameters)
    parameters))

(defn prepare-parameters
  "Prepares Asgard parameters by running them through a series of
   transformations."
  [parameters region]
  (-> parameters
      replace-load-balancer-key
      (replace-security-group-names region)))

(defn explode-parameters
  "Take a map of parameters and turns them into a list of [key value] pairs
   where the same key may appear multiple times. This is used to create the
   form parameters which we pass to Asgard (and may be specified multiple times
   each)."
  [parameters]
  (for [[k v] (seq parameters)
        vs (flatten (conj [] v))]
    [k vs]))

;; # Concerning tracking tasks

(def task-pool
  "A pool which we use to refresh the tasks from Asgard."
  (at-at/mk-pool))

(def finished-states
  "The states at which a task is deemed finished."
  #{"completed" "failed" "terminated"})

(defn finished?
  "Indicates whether the task is completed."
  [task]
  (contains? finished-states (:status task)))

;; We're going to need this in a minute.
(declare track-task)

(defn track-until-completed
  "After a 1s delay, tracks the task by saving its content to the task store
   until it is completed (as indicated by `finished?`) or `count` reaches 0."
  [ticket-id {:keys [url] :as task} count completed-fn timed-out-fn]
  (at-at/after 1000 #(track-task ticket-id task count completed-fn timed-out-fn)
               task-pool :desc url))

(defn track-task
  "Grabs the task by its URL and updates its details in the store. If it's not
   completed and we've not exhausted the retry count it'll reschedule itself."
  [ticket-id {:keys [url] :as task} count completed-fn timed-out-fn]
  (when-let [asgard-task (task-by-url url)]
    (store/store-task (merge task asgard-task))
    (cond (finished? asgard-task) (completed-fn ticket-id task)
          (zero? count) (timed-out-fn ticket-id task)
          :else (track-until-completed ticket-id task (dec count)
                                       completed-fn timed-out-fn))))

;; Handler for recovering from failure while tracking a task. In the event of an
;; exception marked with a `:class` of `:http` or `:store` we'll reschedule.
(with-handler! #'track-task
  clojure.lang.ExceptionInfo
  (fn [e ticket-id task count completed-fn timed-out-fn]
    (let [data (.getData e)]
      (if (or (= (:class data) :http)
              (= (:class data) :store))
        (track-until-completed ticket-id task (dec count)
                               completed-fn timed-out-fn)
        (throw e)))))

;; # Concerning deleting ASGs

(defn delete-asg
  "Begins a delete operation on the specified Auto Scaling Group in the region
   given. Will start tracking the resulting task URL until completed. You can
   assume that a non-explosive call has been successful and the task is being
   tracked."
  [region asg-name ticket-id task completed-fn timed-out-fn]
  (let [parameters {"_action_delete" ""
                    "name" asg-name
                    "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params (explode-parameters
                                                              parameters)})]
    (if (= status 302)
      (track-until-completed ticket-id
                             (merge task {:url
                                          (str (get headers "location") ".json")
                                          :asgardParameters parameters})
                             task-track-count completed-fn timed-out-fn)
      (throw (ex-info "Unexpected status while deleting ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `delete-asg` asserting that the ASG we're
;; attempting to delete exists.
(with-precondition! #'delete-asg
  :asg-exists
  (fn [r a _ _ _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `delete-asg`.
(with-handler! #'delete-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning resizing ASGs

(defn resize-asg
  "Begins a resize operation on the specified Auto Scaling Group in the region
   given. Will start tracking the resulting task URL until completed. You can
   assume that a non-explosive call has been successful and the task is being
   tracked."
  [region asg-name ticket-id task new-size completed-fn timed-out-fn]
  (let [parameters {"_action_resize" ""
                    "minAndMaxSize" new-size
                    "name" asg-name
                    "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params (explode-parameters
                                                              parameters)})]
    (if (= status 302)
      (track-until-completed ticket-id
                             (merge task {:url
                                          (str (get headers "location") ".json")
                                          :asgardParameters parameters})
                             task-track-count completed-fn timed-out-fn)
      (throw (ex-info "Unexpected status while resizing ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `resize-asg` asserting that the ASG we're attempting
;; to resize exists.
(with-precondition! #'resize-asg
  :asg-exists
  (fn [r a _ _ _ _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `resize-asg`.
(with-handler! #'resize-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning enabling traffic for ASGs

(defn enable-asg
  "Begins an enable traffic operation on the specified Auto Scaling Group in the
   region given. Will start tracking the resulting task URL until completed. You
   can assume that a non-explosive call has been successful and the task is
   being tracked."
  [region asg-name ticket-id task completed-fn timed-out-fn]
  (let [parameters {"_action_activate" ""
                    "name" asg-name
                    "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params (explode-parameters
                                                              parameters)})]
    (if (= status 302)
      (track-until-completed ticket-id
                             (merge task {:url
                                          (str (get headers "location") ".json")
                                          :asgardParameters parameters})
                             task-track-count completed-fn timed-out-fn)
      (throw (ex-info "Unexpected status while enabling ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `enable-asg` asserting that the ASG we're attempting
;; to enable exists.
(with-precondition! #'enable-asg
  :asg-exists
  (fn [r a _ _ _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `enable-asg`.
(with-handler! #'enable-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning disabling traffic for ASGs

(defn disable-asg
  "Begins an disable traffic operation on the specified Auto Scaling Group in
   the region given. Will start tracking the resulting task URL until completed.
   You can assume that a non-explosive call has been successful and the task is
   being tracked."
  [region asg-name ticket-id task completed-fn timed-out-fn]
  (let [parameters {"_action_deactivate" ""
                    "name" asg-name
                    "ticket" ticket-id}
        {:keys [status headers] :as response} (http/simple-post
                                               (cluster-index-url region)
                                               {:form-params (explode-parameters
                                                              parameters)})]
    (if (= status 302)
      (track-until-completed ticket-id
                             (merge task {:url
                                          (str (get headers "location") ".json")
                                          :asgardParameters parameters})
                             task-track-count completed-fn timed-out-fn)
      (throw (ex-info "Unexpected status while disabling ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; Precondition attached to `disable-asg` asserting that the ASG we're
;; attempting to disable exists.
(with-precondition! #'disable-asg
  :asg-exists
  (fn [r a _ _ _ _]
    (auto-scaling-group r a)))

;; Handler for a missing ASG attached to `disable-asg`.
(with-handler! #'disable-asg
  {:precondition :asg-exists}
  (fn [e & args] (throw (ex-info "Auto Scaling Group does not exist."
                                {:type ::missing-asg
                                 :args args}))))

;; # Concerning the creation of the first ASG for an application

(defn extract-new-asg-name
  "Extracts the new ASG name from what would be the `Location` header of the
   response when asking Asgard to create a new ASG. Takes something like
   `http://asgard/{region}/autoScaling/show/{asg-name}` and gives back
   `{asg-name}`."
  [url]
  (second (re-find #"/autoScaling/show/(.+)$" url)))

(defn create-new-asg-asgard-parameters
  "Creates the Asgard parameters for creating a new ASG as a combination of the
   various defaults and user-provided parameters."
  [region application-name environment image-id user-parameters ticket-id]
  (let [protected-parameters (protected-create-new-asg-parameters
                              application-name environment image-id ticket-id)]
    (prepare-parameters (merge default-create-new-asg-parameters
                               user-parameters protected-parameters) region)))

(defn create-new-asg
  "Begins a create new Auto Scaling Group operation for the specified
   application and environment in the region given. It __WILL__ start traffic to
   the newly-created ASG. Will start tracking the resulting task URL until
   completed. You can assume that a non-explosive call has been successful and
   the task is being tracked."
  [region application-name environment image-id user-parameters ticket-id task
   completed-fn timed-out-fn]
  (let [asgard-parameters (create-new-asg-asgard-parameters region
                                                            application-name
                                                            environment
                                                            image-id
                                                            user-parameters
                                                            ticket-id)
        {:keys [status headers] :as response}
        (http/simple-post
         (auto-scaling-save-url region)
         {:form-params (explode-parameters asgard-parameters)})]
    (if (= status 302)
      (let [new-asg-name (extract-new-asg-name (get headers "location"))
            tasks (tasks region)
            log-message (str "Create Auto Scaling Group '" new-asg-name "'")]
        (when-let [found-task
                   (first (filter (fn [t] (= (:name t) log-message)) tasks))]
          (let [task-id (:id found-task)
                url (task-by-id-url region task-id)]
            (store/add-to-deployment-parameters
             ticket-id
             {:newAutoScalingGroupName (str application-name "-" environment)})
            (track-until-completed
             ticket-id
             (merge task {:url url
                          :asgardParameters asgard-parameters})
             task-track-count
             completed-fn
             timed-out-fn)))
        new-asg-name)
      (throw (ex-info "Unexpected status while creating new ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; # Concerning the creation of the next ASG for an application-name

(defn create-next-asg-asgard-parameters
  "Creates the Asgard parameters for creating the next ASG as a combination of
   the various defaults and user-provided parameters."
  [region application-name environment image-id user-parameters ticket-id]
  (let [protected-parameters (protected-create-next-asg-parameters
                              application-name environment image-id ticket-id)]
    (prepare-parameters (merge default-create-next-asg-parameters
                               user-parameters protected-parameters) region)))

(defn new-asg-name-from-task
  "Examines the `:message` of the first item in the task's `:log` for a string
   matching `Creating auto scaling group '{new-asg-name}'` and returns
   `new-asg-name`."
  [task-url]
  (let [task (task-by-url task-url)
        pattern #"Creating auto scaling group '([^']+)'"]
    ((re-find pattern (get-in task [:log 0 :message])) 1)))

(defn create-next-asg
  "Begins a create next Auto Scaling Group operation for the specified
   application and environment in the region given. It __WILL NOT__ start
   traffic to the newly-created ASG. Will start tracking the resulting task URL
   until completed. You can assume that a non-explosive call has been successful
   and the task is being tracked."
  [region application-name environment image-id parameters ticket-id task
   completed-fn timed-out-fn]
  (let [asgard-parameters (create-next-asg-asgard-parameters
                           region
                           application-name
                           environment
                           image-id
                           parameters
                           ticket-id)
        {:keys [status headers]
         :as response} (http/simple-post
                        (cluster-create-next-group-url region)
                        {:form-params (explode-parameters asgard-parameters)})]
    (if (= status 302)
      (let [task-json-url (str (get headers "location") ".json")
            new-asg-name (new-asg-name-from-task task-json-url)]
        (store/add-to-deployment-parameters
         ticket-id
         {:newAutoScalingGroupName new-asg-name})
        (track-until-completed ticket-id
                               (merge task
                                      {:url task-json-url
                                       :asgardParameters asgard-parameters})
                               task-track-count completed-fn timed-out-fn))
      (throw (ex-info "Unexpected status while creating next ASG"
                      {:type ::unexpected-response
                       :response response})))))

;; # Concerning creating ASGs for an application

(defn create-auto-scaling-group
  "If the specified application already has an ASG in the given region and
   environment, create the next one. Otherwise, create a brand-new ASG."
  [region application environment ami parameters ticket-id task completed-fn
   timed-out-fn]
  (let [asg-name (str application "-" environment)]
    (if-let [asg (last-auto-scaling-group region asg-name)]
      (let [old-asg-name (:autoScalingGroupName asg)]
        (store/add-to-deployment-parameters
         ticket-id
         {:oldAutoScalingGroupName old-asg-name})
        (create-next-asg region application environment ami parameters ticket-id
                         task completed-fn timed-out-fn))
      (create-new-asg region application environment ami parameters ticket-id
                      task completed-fn timed-out-fn))))
