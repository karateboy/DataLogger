/**
 * 
 */
angular.module('tapiConfigView', [])
.controller('tapiConfigCtrl',
[ 'InstConfigService', 
  '$scope', 
  function($config, $scope) {
	$scope.param = $config.param;
	//console.log($scope.param);
	if(isEmpty($scope.param)){
		$scope.param={
				slaveID:10,
				calibrationTimeDate: new Date(1970, 0, 1, 15, 0, 0),
				raiseTime:300,
				holdTime:60,
				downTime:300
			};
	}else{
		//angular require calibrationTime to be Date		
		$scope.param.calibrationTimeDate = moment($scope.param.calibrationTime, "HH:mm:ss").toDate(); 
	}
	
	$scope.showCalibrationUI = function(){
		return $config.instrumentType != 't700'		
	}
	
	$config.summary = function() {
		var desc = "";
		desc += "<br/>slave ID:" + $scope.param.slaveID;
		if ($config.instrumentType != 't700') {
			if($scope.param.calibrationTimeDate instanceof Date)
				desc += "<br/>校正時間:" + $scope.param.calibrationTimeDate.toLocaleTimeString();
			
			desc += "<br/>校正上升時間:" + $scope.param.raiseTime;
			desc += "<br/>校正持續時間:" + $scope.param.holdTime;
			desc += "<br/>校正下降時間:" + $scope.param.downTime;
		}
		
		return desc;
	}

	$config.validate=function(){
		if(!$scope.param.slaveID){
			alert("沒有設定slaveID!");
			return false;
		}
		
		if($config.instrumentType == 't700')
			return true;
		
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
		
		console.log($scope.param);
		$scope.param.slaveID = parseInt($scope.param.slaveID);
		$scope.param.calibrationTime = $scope.param.calibrationTimeDate.getTime();
		$scope.param.raiseTime = parseInt($scope.param.raiseTime);
		$scope.param.holdTime = parseInt($scope.param.holdTime);
		$scope.param.raiseTime = parseInt($scope.param.raiseTime);
				
		//copy back
		$config.param = $scope.param;
		
		return true;
	}
  } ]);