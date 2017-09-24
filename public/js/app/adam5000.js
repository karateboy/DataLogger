/**
 * 
 */

angular.module('adam5000View', [])
	.controller('adam5000Ctrl', ['InstConfigService', '$scope', function ($config, $scope) {
		function handleConfig(config) {
			var index = 0;
			var paramList = [];

			if (isEmpty(config.param)) {
				console.log("config.param is empty...");
				$scope.param.addr = 0;
				$scope.param.ch = [];
				for (var i = 0; i < 16; i++) {
					$scope.param.ch.push({});
				}
				paramList.push($scope.param);
			} else {
				$scope.param = config.param.moduleList[0];
				paramList = config.param.moduleList;
			}

			$scope.supportedModule = [{
					id: "5051",
					desp: "DI 5051",
					nChannel: 16,
					moduleType: "DI"
				},
				{
					id: "5056",
					desp: "DO 5056",
					nChannel: 16,
					moduleType: "DO"
				},
				{
					id: "5017",
					desp: "AI 5017",
					nChannel: 8,
					moduleType: "AI"
				}
			];

			let getChannelNumber = function (m) {
				for (let module of $scope.supportedModule) {
					if (module.id == m.moduleID)
						return module.nChannel;
				}
				return 0;
			}

			let getModuleType = function (m) {
				for (let module of $scope.supportedModule) {
					if (module.id == m.moduleID)
						return module.moduleType;
				}
				return undefined;
			}

			$scope.channels = function () {
				let nChannel = getChannelNumber($scope.param)
				let chArray = [];
				for (let i = 0; i < nChannel; i++)
					chArray.push(i);

				return chArray;
			};

			$scope.moreDevice = function () {
				index++;
				$scope.param = {};
				$scope.param.addr = 0;
				$scope.param.ch = [];
				for (var i = 0; i < 16; i++) {
					$scope.param.ch.push({});
				}
				paramList.push($scope.param);
			};

			$scope.prevDevice = function () {
				if (index > 0) {
					index--;
					$scope.param = paramList[index];
				}
			};

			$scope.nextDevice = function () {
				if (index + 1 < paramList.length) {
					index++;
					$scope.param = paramList[index];
				}
			};

			config.summary = function () {
				let desc = "";

				for (let idx = 0; idx < paramList.length; idx++) {
					let module = paramList[idx];
					desc += `<br/><strong>${module.moduleID}</strong>`;
					desc += "<br/>位址:" + module.addr;
					let nChannel = getChannelNumber(module);
					let moduleType = getModuleType(module)
					if (moduleType == "DO")
						continue;

					for (var i = 0; i < nChannel; i++) {
						if (module.ch[i].enable) {
							desc += "<br/>CH" + i + ":啟用";

							if (module.ch[i].repairMode)
								desc += "<br/>維修模式:啟用";

							desc += "<br/><strong>測項:" + module.ch[i].mt + "</strong>";

							if (moduleType == "DI")
								continue;

							desc += "<br/>最大值:" + module.ch[i].max;
							desc += "<br/>測項最大值:" + module.ch[i].mtMax;
							desc += "<br/>最小值:" + module.ch[i].min;
							desc += "<br/>測項最小值:" + module.ch[i].mtMin;
						}
					}
				}

				return desc;
			};

			function validateAI(module) {
				console.log("validate AI")
				if (module.addr === null || module.addr === undefined) {
					alert(module.moduleID + ": 位址是空的!");
					return false;
				}
				let nChannel = getChannelNumber(module)
				if(module.ch.length != nChannel){
					console.log("unexpected channel is more than it is!");
					module.ch.splice(nChannel, module.ch.length - nChannel)
				}
				for (var i = 0; i < nChannel; i++) {
					if (module.ch[i].enable) {
						try {
							if (!module.ch[i].mt) {
								alert("請指定測項");
								return false;
							}
							if (module.ch[i].max.length == 0) {
								alert("請指定最大值");
								return false;
							}
							if (module.ch[i].mtMax.length == 0) {
								alert("請指定測項最大值");
								return false;
							}
							if (module.ch[i].min.length == 0) {
								alert("請指定最小值");
								return false;
							}
							if (module.ch[i].mtMin.length == 0) {
								alert("請指定測項最小值");
								return false;
							}

							module.ch[i].max = parseFloat(module.ch[i].max);
							module.ch[i].mtMax = parseFloat(module.ch[i].mtMax);
							module.ch[i].min = parseFloat(module.ch[i].min);
							module.ch[i].mtMin = parseFloat(module.ch[i].mtMin);
						} catch (ex) {
							alert(ex.toString());
							return false;
						}
					} else
						module.ch[i].enable = false;
				}
				return true;
			}

			function validateDI(module) {
				console.log("validate DI")
				if (module.addr === null || module.addr === undefined) {
					alert(module.moduleID + ": 位址是空的!");
					return false;
				}

				for (var i = 0; i < 16; i++) {
					if (module.ch[i].enable) {
						try {
							if (!module.ch[i].mt) {
								alert("請指定測項");
								return false;
							}
						} catch (ex) {
							alert(ex.toString());
							return false;
						}
					} else
						module.ch[i].enable = false;
				}
				return true;
			}

			function validateDO(module) {
				console.log("validate DO")
				if (module.addr === null || module.addr === undefined) {
					alert(module.moduleID + ": 位址是空的!");
					return false;
				}

				for (var i = 0; i < 16; i++) {
					module.ch[i].enable = true;
				}
				return true;
			}

			config.validate = function () {
				for (var idx = 0; idx < paramList.length; idx++) {
					var module = paramList[idx];
					var ret = true;
					let moduleType = getModuleType(module);
					switch (moduleType) {
						case "AI":
							ret = validateAI(module);
							break;
						case "DI":
							ret = validateDI(module);
							break;
						case "DO":
							ret = validateDO(module);
							break;
					}


					if (!ret)
						return ret;
				}

				//copy back
				config.param = {
					moduleList: paramList
				};
				return true;
			};
		} //End of handleConfig
		handleConfig($config);
		$config.subscribeConfigChanged($scope, function (event, config) {
			handleConfig(config);
		});
	}]);