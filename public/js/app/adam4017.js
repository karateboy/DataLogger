/**
 * 
 */

angular.module('adam4017View',[])
.controller('adam4017Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
	$scope.channels=[0, 1, 2, 3, 4, 5, 6, 7];
	$scope.param=$config.param;
	if(isEmpty($scope.param)){
		console.log("config.param is empty...");		
		$scope.param.addr = '01';
		$scope.param.ch=[];
		for(var i=0;i<8;i++){
			$scope.param.ch.push({});
		}
	}
	
	$config.summary = function(){
		var desc="";
		desc += "<br/>位址:" + $scope.param.addr;
		for(var i=0;i<8;i++){
			if($scope.param.ch[i].enable){
				desc += "<br/>CH"+i +":啟用";			
				desc += "<br/><strong>測項:" + $scope.param.ch[i].mt + "</strong>";
				desc += "<br/>最大值:" + $scope.param.ch[i].max;
				desc += "<br/>測項最大值:" + $scope.param.ch[i].mtMax;
				desc += "<br/>最小值:" + $scope.param.ch[i].min;
				desc += "<br/>測項最小值:" + $scope.param.ch[i].mtMin;						
			}	
		}
		
		return desc;
	}
	
	$config.validate=function(){
		if($scope.param.addr.length == 0){
			alert("位址是空的!");
			return false;
		}

		for(var i=0;i<8;i++){
			if($scope.param.ch[i].enable){
				try{
					if($scope.param.ch[i].max.length == 0){
						alert("請指定最大值");
						return false;
					}
					if($scope.param.ch[i].mtMax.length == 0){
						alert("請指定測項最大值");
						return false;
					}
					if($scope.param.ch[i].min.length == 0){
						alert("請指定最小值");
						return false;
					}
					if($scope.param.ch[i].mtMin.length == 0){
						alert("請指定測項最小值");
						return false;
					}				
					
					$scope.param.ch[i].max = parseFloat($scope.param.ch[i].max);
					$scope.param.ch[i].mtMax = parseFloat($scope.param.ch[i].mtMax);
					$scope.param.ch[i].min = parseFloat($scope.param.ch[i].min);
					$scope.param.ch[i].mtMin = parseFloat($scope.param.ch[i].mtMin);
				}catch(ex){
					alert(ex.toString());
					return false;
				}
			}else
				$scope.param.ch[i].enable = false;
		}
		return true;
	}
}]);