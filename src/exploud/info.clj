(ns exploud.info
  "## For grabbing information about the things we're dealing with"
  (:require [exploud
             [asgard :as asgard]
             [onix :as onix]
             [tyranitar :as tyr]
             [shuppet :as shuppet]]))

(defn applications
  "The list of applications Onix knows about."
  []
  (onix/applications))

(defn application
  "The information about a particular application."
  [environment region application-name]
  (if-let [full-application (asgard/application environment region application-name)]
    (merge {:name application-name} (select-keys (:app full-application) [:description :email :owner]))))

(defn upsert-application
  "Upserts an application into Onix, Tyranitar, Shuppet and Asgard. This function
   can be run many times, it won't fail if the application is present in any of the
   stores."
  [region application-name details]
  (let [onix-application (onix/upsert-application application-name)
        tyranitar-application (tyr/upsert-application application-name)
        shuppet-application (shuppet/upsert-application application-name)]
    (asgard/upsert-application application-name details)
    (shuppet/apply-config application-name)
    (merge (application "poke" region application-name) tyranitar-application shuppet-application)))

(defn- is-named?
  "Checks if the given instance has the given name (ignoring version)."
  [app-name instance]
  (let [tags (get-in instance [:ec2Instance :tags])
        pattern (re-pattern (str app-name ".*"))]
    (some #(and (= "Name" (:key %)) (re-matches pattern (:value %))) tags)))

(defn instances-for-application
  "Gets those instances in the given region, which have the given name (ignoring version)."
  [region application-name]
  (let [all-instances (asgard/all-instances region)]
    (filter #(is-named? application-name %) all-instances)))

(defn- matching-config-id
  "Gets the launch config name from the given config if it matches the given app name."
  [config application-name]
  (let [pattern (re-pattern (str application-name ".*"))
        config-name (:launchConfigurationName config)]
    (when (re-matches pattern config-name)
      config-name)))

(defn- matching-config-ids
  "Gets the launch config name from the given configs where it matches the given app name. Removes 'nil' results."
  [configs app-name]
  (let [app-config-ids (map #(matching-config-id % app-name) configs)]
    (filter identity app-config-ids)))

(defn- active-images
  "Gets the image data for each given launch-config ID, if the config is active."
  [config-ids region]
  (let [configs (map #(asgard/launch-config region %) config-ids)
        active-configs (filter #(identity (:group %)) configs)]
    (map :image active-configs) ))

(defn active-amis-for-app
  "Fetches all active amis for the given application in the given region."
  [region app-name]
  (->
   (asgard/launch-config-list region)
   (matching-config-ids app-name)
   (active-images region)))

(defn environments
  "Retrieves a list of all the environments (or stacks) that all Asgards know about."
  [region]
  (asgard/stacks region))
