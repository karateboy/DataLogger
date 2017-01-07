/**
 * 
 */
angular.module('baselineConfigView', [])
.controller('baselineConfigCtrl',
[ 'InstConfigService', 
  '$scope', 
  function($config, $scope) {
	function handleConfig(config){
		$scope.param = $config.param;

		if(isEmpty($scope.param)){
			$scope.param={
					calibrationTimeDate: new Date(1970, 0, 1, 15, 0, 0),
					raiseTime:300,
					holdTime:60,
					downTime:300,
					calibrateZeoSeq:1,
					calibrateSpanSeq:2
				};
		}else{
			//angular require calibrationTime to be Date		
			$scope.param.calibrationTimeDate = moment($scope.param.calibrationTime, "HH:mm:ss").toDate(); 
		}
		
		config.summary = function() {
			var desc = "";
			{
				if($scope.param.calibrationTimeDate instanceof Date)
					desc += "<br/>校正時間:" + $scope.param.calibrationTimeDate.toLocaleTimeString();
				
				desc += "<br/>校正上升時間:" + $scope.param.raiseTime;
				desc += "<br/>校正持續時間:" + $scope.param.holdTime;
				desc += "<br/>校正下降時間:" + $scope.param.downTime;
				desc += "<br/>零點校正執行程序:" + $scope.param.calibrateZeoSeq;
				desc += "<br/>全幅校正執行程序:" + $scope.param.calibrateSpanSeq;
				if($scope.param.calibratorPurgeTime)
					desc += "<br/>校正器清空時間:" + $scope.param.calibratorPurgeTime;
				if($scope.param.calibratorPurgeSeq)
					desc += "<br/>校正器清空執行程序:" + $scope.param.calibratorPurgeSeq;

			}
			
			return desc;
		}

		config.validate=function(){
			
			if(!$scope.param.calibrationTimeDate){
				alert("沒有設定校正時間!");
				return false;
			}

			if(!$scope.param.raiseTime){
				alert("沒有設定校正上升時間!");
				return false;
			}

			if(!$scope.param.holdTime){
				alert("沒有設定校正持續時間!");
				return false;
			}
			
			if(!$scope.param.downTime){
				alert("沒有設定校正下降時間!");
				return false;
			}
			if(!$scope.param.calibrateZeoSeq){
				alert("沒有設定零點校正程序!");
				return false;
			}

			if(!$scope.param.calibrateSpanSeq){
				alert("沒有設定全幅校正程序!");
				return false;
			}

			$scope.param.calibrationTime = $scope.param.calibrationTimeDate.getTime();
			$scope.param.raiseTime = parseInt($scope.param.raiseTime);
			$scope.param.holdTime = parseInt($scope.param.holdTime);
			$scope.param.downTime = parseInt($scope.param.downTime);
			$scope.param.calibrateZeoSeq = parseInt($scope.param.calibrateZeoSeq);
			$scope.param.calibrateSpanSeq = parseInt($scope.param.calibrateSpanSeq);
			
			if($scope.param.calibrateZeoSeq)
				$scope.param.calibrateZeoSeq = parseInt($scope.param.calibrateZeoSeq);
			
			if($scope.param.calibrateSpanSeq)
				$scope.param.calibrateSpanSeq = parseInt($scope.param.calibrateSpanSeq);

			//copy back
			config.param = $scope.param;
			
			return true;
		}		
	}//end of handlConfig
	handleConfig($config);
	$config.subscribeConfigChanged($scope, function(event, config){
		handleConfig(config);
		});
  } ]);