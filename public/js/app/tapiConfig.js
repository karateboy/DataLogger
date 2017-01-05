/**
 * 
 */
angular.module('tapiConfigView', [])
.controller('tapiConfigCtrl',
[ 'InstConfigService', 
  '$scope', 
  function($config, $scope) {
	function handleConfig(config){
		$scope.param = config.param;
		if(isEmpty($scope.param)){
			$scope.param={
					slaveID:10,
					calibrationTimeDate: new Date(1970, 0, 1, 15, 0, 0),
					raiseTime:300,
					holdTime:60,
					downTime:300,
					calibrateZeoSeq:1,
					calibrateSpanSeq:2,
					calibratorPurgeSeq:3,
					calibratorPurgeTime:0,
				};
		}else{
			//angular require calibrationTime to be Date		
			$scope.param.calibrationTimeDate = moment($scope.param.calibrationTime, "HH:mm:ss").toDate(); 
		}
		
		$scope.showCalibrationUI = function(){
			return config.instrumentType != 't700'		
		}
		
		config.summary = function() {
			var desc = "";
			desc += "<br/>slave ID:" + $scope.param.slaveID;
			if ($config.instrumentType != 't700') {
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
				if($scope.param.calibrateZeoDO)
					desc += "<br/>零點校正DO:" + $scope.param.calibrateZeoDO;
				if($scope.param.calibrateSpanDO)
					desc += "<br/>全幅校正DO:" + $scope.param.calibrateSpanDO;
				
			}
			
			return desc;
		}

		config.validate=function(){
			if(!$scope.param.slaveID){
				alert("沒有設定slaveID!");
				return false;
			}
			
			if($config.instrumentType == 't700'){
				$scope.param.slaveID = parseInt($scope.param.slaveID);
				//copy back
				$config.param = $scope.param;
				
				return true;
			}
				
			
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
			
			$scope.param.slaveID = parseInt($scope.param.slaveID);
			$scope.param.calibrationTime = $scope.param.calibrationTimeDate.getTime();
			$scope.param.raiseTime = parseInt($scope.param.raiseTime);
			$scope.param.holdTime = parseInt($scope.param.holdTime);
			$scope.param.downTime = parseInt($scope.param.downTime);
			
			if($scope.param.calibrateZeoSeq)
				$scope.param.calibrateZeoSeq = parseInt($scope.param.calibrateZeoSeq);
			
			if($scope.param.calibrateSpanSeq)
				$scope.param.calibrateSpanSeq = parseInt($scope.param.calibrateSpanSeq);
			
			if($scope.param.calibratorPurgeSeq)
				$scope.param.calibratorPurgeSeq = parseInt($scope.param.calibratorPurgeSeq);
			
			if($scope.param.calibratorPurgeTime)
				$scope.param.calibratorPurgeTime = parseInt($scope.param.calibratorPurgeTime);
			
			if($scope.param.calibrateZeoDO)
				$scope.param.calibrateZeoDO = parseInt($scope.param.calibrateZeoDO);
			
			if($scope.param.calibrateSpanDO)
				$scope.param.calibrateSpanDO = parseInt($scope.param.calibrateSpanDO);

			//copy back
			config.param = $scope.param;			
		    return true;		
	    }
	}//End of handleConfig
	handleConfig($config);
	$config.subscribeConfigChanged($scope, function(event, config){
		handleConfig(config);
		});
  } ]);